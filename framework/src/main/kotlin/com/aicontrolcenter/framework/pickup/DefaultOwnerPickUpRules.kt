package com.aicontrolcenter.framework.pickup

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.pickUpRuleId
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.framework.operator.logSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The owner's Folder pick-up screen (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17): rules "pick up new
 * text files from shared folder F into memory space S", over the [PickUpRuleRepository], every change logged as the
 * owner's action.
 *
 * What it promises (`OwnerPickUpRulesContract`; security review of wave 1, F1 and its second review):
 *  - A rule is made, or switched on, only for a folder that exists, is read-only for agents and overlaps no shared
 *    folder agents may change ([PickUpSafety.folderProblem]); otherwise the owner is told what to do and nothing is
 *    stored or switched on. Switching off always works.
 *  - Making a rule, or switching one on, first stores it switched off, records every file now in the folder as seen
 *    ([PickUpSafety.baseline]; never imported by a run, because it may have been written while agents could change
 *    the folder), checks the folder once more, then switches it on under the folder's `readOnlySince`. A folder that
 *    cannot be read for that refuses with [PickUpSafety.FOLDER_UNREADABLE] (a new rule is then removed again).
 *  - One rule per folder and at most [PickUpLimits.MAX_RULES], under one lock, so creations at once cannot pass either.
 *  - The space must exist ([spaceExists]); the memories a rule made stay when it is deleted.
 *
 * It reads folders only through [PickUpFolders] (records, the files directly in a folder, reading one) and imports
 * nothing itself: [PickUpRunner] does, as an upkeep step. Nothing an agent reaches holds this class (both
 * architecture tests list [OwnerPickUpRules] and [PickUpRuleRepository] as owner-only).
 */
class DefaultOwnerPickUpRules(
    private val repository: PickUpRuleRepository,
    private val folders: PickUpFolders,
    /** Whether the memory space exists now. */
    private val spaceExists: suspend (SpaceId) -> Boolean,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val ids: IdGenerator,
) : OwnerPickUpRules {
    private val lock = Mutex()

    override fun observeAll(): Flow<List<PickUpRule>> = repository.observeAll()

    override suspend fun create(folderId: FolderId, spaceId: SpaceId): OwnerResult<PickUpRule> {
        val (result, folder) = lock.withLock {
            val all = folders.all()
            val folder = all.firstOrNull { it.id == folderId }
            PickUpSafety.folderProblem(folder, all)?.let { return OwnerResult.Refused(it) }
            folder!!
            if (!spaceExists(spaceId)) return OwnerResult.Refused(NO_SPACE)
            val rules = repository.getAll()
            if (rules.any { it.folderId == folderId }) return OwnerResult.Refused(ONE_PER_FOLDER)
            if (rules.size >= PickUpLimits.MAX_RULES) return OwnerResult.Refused(TOO_MANY)
            val now = clock.now()
            // Stored switched off until what is in the folder now is recorded as seen.
            val rule = PickUpRule(
                id = ids.pickUpRuleId(), folderId = folderId, spaceId = spaceId, enabled = false, createdAt = now, updatedAt = now,
                folderReadOnlySince = folder.readOnlySince,
            )
            repository.upsert(rule)
            val on = switchOn(rule, folder.readOnlySince, now)
            if (on !is OwnerResult.Done) repository.delete(rule.id)
            on to folder
        }
        if (result is OwnerResult.Done) log("Folder pick-up made", result.value, folder, SEEN_NOTE)
        return result
    }

    /**
     * Records the files in [rule]'s folder as seen, then switches it on under [readOnlySince], unless the folder changed
     * meanwhile: [PickUpSafety.FOLDER_UNREADABLE] when the folder could not be read, the folder's problem when it has
     * one now.
     */
    private suspend fun switchOn(rule: PickUpRule, readOnlySince: Long?, now: Long): OwnerResult<PickUpRule> {
        val seen = PickUpSafety.baseline(rule, folders, now) ?: return OwnerResult.Refused(PickUpSafety.FOLDER_UNREADABLE)
        seen.forEach { repository.recordImport(it) }
        val all = folders.all()
        val folder = all.firstOrNull { it.id == rule.folderId }
        PickUpSafety.folderProblem(folder, all)?.let { return OwnerResult.Refused(it) }
        if (folder!!.readOnlySince != readOnlySince) return OwnerResult.Refused(PickUpSafety.FOLDER_NOT_READ_ONLY)
        val on = repository.update(rule.id) { it.copy(enabled = true, folderReadOnlySince = readOnlySince, updatedAt = now, lastResult = null) }
        return on?.let { OwnerResult.Done(it) } ?: OwnerResult.Refused(MISSING)
    }

    override suspend fun changeSpace(id: PickUpRuleId, spaceId: SpaceId): OwnerResult<PickUpRule> {
        val changed = lock.withLock {
            if (!spaceExists(spaceId)) return OwnerResult.Refused(NO_SPACE)
            val now = clock.now()
            repository.update(id) { it.copy(spaceId = spaceId, updatedAt = now) } ?: return OwnerResult.Refused(MISSING)
        }
        log("Folder pick-up now goes into another space", changed, folders.folder(changed.folderId))
        return OwnerResult.Done(changed)
    }

    override suspend fun setEnabled(id: PickUpRuleId, enabled: Boolean): OwnerResult<PickUpRule> {
        val result = lock.withLock {
            val now = clock.now()
            if (!enabled) {
                repository.update(id) { it.copy(enabled = false, updatedAt = now) }?.let { OwnerResult.Done(it) } ?: return OwnerResult.Refused(MISSING)
            } else {
                val rule = repository.get(id) ?: return OwnerResult.Refused(MISSING)
                val all = folders.all()
                val folder = all.firstOrNull { it.id == rule.folderId }
                PickUpSafety.folderProblem(folder, all)?.let { return OwnerResult.Refused(it) }
                if (rule.enabled && rule.folderReadOnlySince == folder!!.readOnlySince) return OwnerResult.Done(rule)
                // Off while what is in the folder now is recorded as seen, then on under the folder's present read-only time.
                val off = repository.update(id) { it.copy(enabled = false, updatedAt = now) } ?: return OwnerResult.Refused(MISSING)
                switchOn(off, folder!!.readOnlySince, now)
            }
        }
        if (result is OwnerResult.Done) {
            val rule = result.value
            log(if (enabled) "Folder pick-up switched on" else "Folder pick-up switched off", rule, folders.folder(rule.folderId), if (enabled) SEEN_NOTE else "")
        }
        return result
    }

    override suspend fun delete(id: PickUpRuleId): Boolean {
        val gone = lock.withLock {
            val existing = repository.get(id) ?: return false
            repository.delete(id)
            existing
        }
        log("Folder pick-up deleted", gone, folders.folder(gone.folderId), "The memories it made are kept.")
        return true
    }

    private suspend fun log(title: String, rule: PickUpRule, folder: SharedFolder?, detail: String = "") = logSafely {
        val named = folder?.let { "$title: ${it.displayName.take(PickUpSafety.MAX_SOURCE_NAME_CHARS)}" } ?: title
        activity.record(
            ActivityType.USER_ACTION, named, detail,
            data = buildJsonObject {
                put("pickUpRuleId", rule.id.value)
                put("folderId", rule.folderId.value)
                put("spaceId", rule.spaceId.value)
                put("enabled", rule.enabled)
            },
        )
    }

    /** Texts. */
    companion object {
        const val NO_SPACE: String = "That memory space is no longer there."
        const val MISSING: String = "This pick-up rule no longer exists."
        const val ONE_PER_FOLDER: String = "This folder already has a pick-up rule."
        const val TOO_MANY: String = "You can have at most ${PickUpLimits.MAX_RULES} pick-up rules. Delete one first."

        /** Logged when a rule is made or switched on. */
        const val SEEN_NOTE: String = "Files already in the folder are left out; only files that arrive later are picked up."
    }
}
