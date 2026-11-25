package com.aicontrolcenter.framework.pickup

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.operator.logSafely
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId

/**
 * Automatic folder pick-up (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17): one run of every rule, as a
 * step of the upkeep job, while nobody watches. It reads only through [PickUpFolders] and writes only memories (and
 * each rule's import records and last result); it never reaches a model, a tool or a provider (rule 1): the memory
 * service of the upkeep job indexes with the offline hashing embedder.
 *
 * For each rule, at every run (security review of wave 1, F1 and its second review):
 *  1. Nothing while a backup is restored ([MaintenanceGate.busy]). A rule that is off is left alone (its last result
 *     says why it is off).
 *  2. The rule and every folder are read afresh and [PickUpSafety.runProblem] is asked: the master switch
 *     (`folderPickUpEnabled`, off after installing) is on, the folder exists, is read-only for agents, has stayed so
 *     since the rule was switched on, and overlaps no folder agents may change, nor may hold the same files as one
 *     through another app. On a sentence nothing is imported, the sentence becomes the rule's last result, and when
 *     the folder is the reason the rule is switched off ([PickUpSafety.switchesRuleOff]).
 *  3. The files directly in the folder are listed. Only `text/…` files ([PickUpLimits.accepts]) of at most
 *     [PickUpLimits.MAX_FILE_BYTES] are read; each one's content hash ([PickUpSafety.contentHash]) is looked up and a
 *     file the rule has seen or imported before (by content, whatever its name) is skipped. Text that is not UTF-8 is
 *     skipped. All rules together import at most [PickUpLimits.MAX_BYTES_PER_DAY] a local day; what does not fit waits
 *     for the next day.
 *  4. [PickUpSafety.runProblem] is asked again after reading and before storing anything; on a sentence nothing of
 *     this run is stored.
 *  5. Each file becomes memories made by [PickUpSafety.memoryDraft] (a NOTE in the rule's space, origin IMPORT, source
 *     "Picked up from <folder>", tag `picked-up`), written as the owner's import so the chosen space accepts it and the
 *     memory service keeps IMPORT; never origin OWNER. A file longer than one memory holds ([PART_CHARS]) is split into
 *     parts titled "<name> (part 2 of 3)". Then the import is recorded; if another run recorded the same content first,
 *     this run's memories are taken back.
 *
 * Runs are serialised by one lock.
 */
class PickUpRunner(
    private val rules: PickUpRuleRepository,
    private val folders: PickUpFolders,
    private val memory: MemoryService,
    private val settings: SettingsView,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val gate: MaintenanceGate,
    /** Where each run that imported something, or stopped a rule, is logged; null: not logged. */
    private val activity: ActivityRecorder? = null,
) {
    private val lock = Mutex()

    /** What one run of one rule came to. */
    data class RuleRun(val ruleId: PickUpRuleId, val imported: Int, val result: String?)

    /** Runs every rule once. Returns what each did (rules that were off or skipped whole are not listed). */
    suspend fun runAll(): List<RuleRun> = lock.withLock {
        if (gate.busy.value) return@withLock emptyList()
        rules.getAll().take(PickUpLimits.MAX_RULES).mapNotNull { rule ->
            if (!rule.enabled) return@mapNotNull null
            try {
                runOne(rule.id)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                // One rule's trouble (a folder or store that failed) never keeps the other rules from running.
                RuleRun(rule.id, 0, null)
            }
        }
    }

    private suspend fun runOne(id: PickUpRuleId): RuleRun? {
        val masterOn = settings.get().folderPickUpEnabled
        val now = clock.now()
        val first = check(id, masterOn, now) ?: return null
        val (rule, folder) = when (first) {
            is Check.Stop -> return first.run
            is Check.Go -> first.rule to first.folder
        }
        val listed = folders.files(rule.folderId)
        if (listed !is DocResult.Ok) return finish(rule, now, UNREADABLE, imported = 0)
        val candidates = ArrayList<Candidate>()
        val skipped = Skipped()
        var room = PickUpLimits.MAX_BYTES_PER_DAY - rules.bytesImportedSince(dayStart(now))
        val seenNow = HashSet<String>()
        for (entry in listed.value.sortedWith(compareBy<DocumentEntry>({ it.modifiedAt ?: Long.MAX_VALUE }, { it.name }))) {
            if (entry.isFolder || !PickUpLimits.accepts(entry.mimeType)) continue
            if ((entry.sizeBytes ?: 0) > PickUpLimits.MAX_FILE_BYTES) {
                skipped.tooLarge++
                continue
            }
            val content = (folders.read(rule.folderId, entry.path, PickUpLimits.MAX_FILE_BYTES) as? DocResult.Ok)?.value
            if (content == null) {
                skipped.unreadable++
                continue
            }
            if (content.truncated) {
                skipped.tooLarge++
                continue
            }
            val hash = PickUpSafety.contentHash(content.bytes)
            if (!seenNow.add(hash) || rules.wasImported(rule.id, hash)) continue
            val text = utf8(content.bytes)
            if (text == null) {
                skipped.notText++
                continue
            }
            if (text.isBlank()) continue
            val size = content.bytes.size.toLong()
            if (size > room) {
                skipped.overToday++
                continue
            }
            room -= size
            candidates += Candidate(entry.name, hash, size, text)
        }
        // Once more, after reading and before storing anything: the folder may have changed while it was read.
        val second = check(id, settings.get().folderPickUpEnabled, now) ?: return null
        val (checked, checkedFolder) = when (second) {
            is Check.Stop -> return second.run
            is Check.Go -> second.rule to second.folder
        }
        if (checked.spaceId != rule.spaceId || checkedFolder.id != folder.id) return null
        var imported = 0
        for (candidate in candidates) {
            when (import(checked, checkedFolder, candidate, now)) {
                Import.DONE -> imported++
                Import.ALREADY -> Unit
                Import.NO_SPACE -> return finish(checked, now, NO_SPACE, imported)
            }
        }
        return finish(checked, now, summary(imported, skipped), imported)
    }

    /** The outcome of the safety check of one rule at one moment. */
    private sealed interface Check {
        data class Go(val rule: PickUpRule, val folder: SharedFolder) : Check

        data class Stop(val run: RuleRun) : Check
    }

    /** [PickUpSafety.runProblem] on the rule and every folder read now; null when the rule is gone or was switched off. */
    private suspend fun check(id: PickUpRuleId, masterOn: Boolean, now: Long): Check? {
        val rule = rules.get(id) ?: return null
        if (!rule.enabled) return null
        val all = folders.all()
        val folder = all.firstOrNull { it.id == rule.folderId }
        val problem = PickUpSafety.runProblem(rule, folder, all, masterOn) ?: return Check.Go(rule, folder!!)
        // Nothing is imported; the sentence becomes the rule's last result, and when the folder is the reason the rule stops.
        val stops = PickUpSafety.switchesRuleOff(problem)
        rules.update(id) { it.copy(enabled = if (stops) false else it.enabled, lastRunAt = now, lastResult = problem, updatedAt = if (stops) now else it.updatedAt) }
        if (stops) log("Folder pick-up switched off", problem, rule, folder, Severity.WARNING)
        return Check.Stop(RuleRun(id, 0, problem))
    }

    private class Candidate(val name: String, val hash: String, val size: Long, val text: String)

    private class Skipped(var tooLarge: Int = 0, var notText: Int = 0, var unreadable: Int = 0, var overToday: Int = 0)

    private enum class Import { DONE, ALREADY, NO_SPACE }

    /** Writes [candidate] as memories, then records it; takes the memories back when the content was recorded meanwhile. */
    private suspend fun import(rule: PickUpRule, folder: SharedFolder, candidate: Candidate, now: Long): Import {
        val parts = parts(candidate.text)
        val made = ArrayList<MemoryId>()
        try {
            for ((index, part) in parts.withIndex()) {
                val title = if (parts.size == 1) candidate.name else "${candidate.name} (part ${index + 1} of ${parts.size})"
                made += memory.remember(MemoryActor.Owner, PickUpSafety.memoryDraft(rule, folder, title, part, now)).id
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            takeBack(made)
            return Import.NO_SPACE
        }
        val recorded = rules.recordImport(
            PickedUpFile(rule.id, candidate.hash, candidate.name.take(MAX_NAME_CHARS), candidate.size, now, memoryId = made.firstOrNull(), seenOnly = false),
        )
        if (!recorded) {
            takeBack(made)
            return Import.ALREADY
        }
        return Import.DONE
    }

    private suspend fun takeBack(made: List<MemoryId>) {
        for (id in made) {
            try {
                memory.forget(MemoryActor.Owner, id)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
            }
        }
    }

    private suspend fun finish(rule: PickUpRule, now: Long, result: String, imported: Int): RuleRun {
        rules.update(rule.id) { it.copy(lastRunAt = now, lastResult = result) }
        if (imported > 0) log("Folder pick-up", result, rule, folders.folder(rule.folderId), Severity.INFO)
        return RuleRun(rule.id, imported, result)
    }

    private fun dayStart(now: Long): Long {
        val zone = zone()
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private suspend fun log(title: String, detail: String, rule: PickUpRule, folder: SharedFolder?, severity: Severity) {
        val recorder = activity ?: return
        logSafely {
            recorder.record(
                ActivityType.MEMORY, folder?.let { "$title: ${it.displayName.take(PickUpSafety.MAX_SOURCE_NAME_CHARS)}" } ?: title, detail,
                severity = severity,
                data = buildJsonObject {
                    put("pickUpRuleId", rule.id.value)
                    put("spaceId", rule.spaceId.value)
                },
            )
        }
    }

    /** Texts and bounds. */
    companion object {
        /** The most a memory holds (the memory service's limit); a longer file is split into parts. */
        const val PART_CHARS: Int = 20_000

        private const val MAX_NAME_CHARS: Int = 255

        const val UNREADABLE: String = "Nothing was picked up: the folder could not be read just now. It is tried again at the next pick-up."
        const val NO_SPACE: String =
            "Nothing more was picked up: a file could not be saved into the memory space (if the space was deleted, choose another one). " +
                "It is tried again at the next pick-up."
        const val NOTHING_NEW: String = "Nothing new to pick up."

        /** The rule's last result after a run that read the folder. */
        private fun summary(imported: Int, skipped: Skipped): String = buildList {
            add(
                when (imported) {
                    0 -> NOTHING_NEW
                    1 -> "Picked up 1 file."
                    else -> "Picked up $imported files."
                },
            )
            if (skipped.overToday > 0) add("${files(skipped.overToday)} left for tomorrow: today's limit of 5 MB is reached.")
            if (skipped.tooLarge > 0) add("${files(skipped.tooLarge)} larger than 1 MB, so not picked up.")
            if (skipped.notText > 0) add("${files(skipped.notText)} not readable as text, so not picked up.")
            if (skipped.unreadable > 0) add("${files(skipped.unreadable)} could not be read; tried again next time.")
        }.joinToString(" ")

        private fun files(n: Int) = if (n == 1) "1 file was" else "$n files were"

        /** [bytes] as UTF-8 text (a byte-order mark left out), or null when they are not valid UTF-8. */
        internal fun utf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (_: CharacterCodingException) {
            null
        }

        /**
         * [text] in parts of at most [PART_CHARS] characters, each cut after a line break or a space where one is near the
         * end, never inside a surrogate pair; blank parts are left out.
         */
        internal fun parts(text: String, max: Int = PART_CHARS): List<String> {
            val parts = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + max, text.length)
                if (end < text.length) {
                    val window = text.substring(start, end)
                    val cut = maxOf(window.lastIndexOf('\n'), window.lastIndexOf(' '))
                    if (cut > max / 2) end = start + cut + 1
                    if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                }
                val part = text.substring(start, end)
                if (part.isNotBlank()) parts += part.trim()
                start = end
            }
            return parts
        }
    }
}
