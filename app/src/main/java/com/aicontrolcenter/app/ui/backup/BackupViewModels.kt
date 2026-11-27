package com.aicontrolcenter.app.ui.backup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.formatBytes
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.Passphrases
import com.aicontrolcenter.core.backup.RestoreSettings
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.data.backup.BackupTexts
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import com.aicontrolcenter.data.backup.RestorePreview
import com.aicontrolcenter.data.backup.SavePassphrase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/*
 * The backup screens' view models (Tier B; rules 8, 21, 27, 30; WP10b). Texts are WP3's `BackupTexts` and core's
 * `BackupProblem` sentences, as they are. The passphrase never leaves the view model but to the backup port: it is not
 * in any state the screen shows (only the generated one, grouped, while the save screen is open), never logged, and
 * emptied (with the field's undo history) when the screen closes.
 */

/** A step of a save, an opening or a restore: [fraction] null means "no measure" (the key step), shown without a bar's end. */
@Immutable
data class BackupStepUi(val step: String, val fraction: Float?) {
    /** What a screen reader says for it. */
    val spoken: String get() = if (fraction == null) "$step. This can take a while." else "$step: ${(fraction * 100).toInt()} percent"

    companion object {
        fun of(progress: BackupProgress): BackupStepUi {
            val total = progress.total
            val fraction = if (total == null || total <= 0) null else (progress.done.toFloat() / total).coerceIn(0f, 1f)
            return BackupStepUi(progress.step, fraction)
        }
    }
}

/** What a finished save says. */
@Immutable
data class SavedUi(val what: String, val size: String, val notes: List<String>)

/**
 * The file the owner chose to save a backup in. It may be a new, empty file the picker just made, or a file that already
 * exists (Android's picker asks "Overwrite …?"). A save writes it only once the whole backup is ready (SEC-W3-1).
 */
interface ChosenBackupFile {
    /** Its size now, in bytes, or null when its app does not say. */
    suspend fun size(): Long?

    /** Puts the ready backup [ready] in its place, replacing what it held; [ReplaceFailed] when that did not work. */
    suspend fun replaceWith(ready: File)

    /** Removes it. Called only for a file that was empty when the save started and still is. */
    suspend fun remove()
}

/** The ready backup could not be put into the chosen file; [chosenUntouched] when that file is still as it was. */
class ReplaceFailed(val chosenUntouched: Boolean) : IOException("The backup could not be written into the chosen file.")

/** What the Save a backup screen shows. */
@Immutable
data class BackupSaveUiState(
    val isLoading: Boolean = false,
    /** The generated passphrase in groups ("7KQ2-M9XD-…"), shown while this screen is open; null once forgotten. */
    val generated: String? = null,
    /** True when the owner chose "Use my own". */
    val useOwn: Boolean = false,
    /** The owner said they wrote the generated passphrase down. */
    val wroteItDown: Boolean = false,
    /** True while the file is being saved. */
    val working: Boolean = false,
    /** True while Stop is offered: not once the ready backup is being put into the chosen file (that takes moments). */
    val cancellable: Boolean = false,
    /**
     * True while the owner is asked "Stop saving?". A backup that gets ready meanwhile waits for the answer and does not
     * go into the chosen file, so Stop always does what the question says (SEC-W3-1).
     */
    val stopAsked: Boolean = false,
    val step: BackupStepUi? = null,
    val saved: SavedUi? = null,
    /** Why the last save did nothing, in the owner's words. */
    val failure: String? = null,
)

/**
 * Saving a backup file (Tier B, rules 8, 21, 27; WP10b): a generated passphrase by default ([SavePassphrase], shown once
 * with `BackupTexts.WRITE_IT_DOWN`), "Use my own" ([ownPassphrase], typed twice: [ownPassphraseAgain]; only through
 * `Passphrases.problemForSave`, with its plain reasons), "keep the app open", the key step with Cancel (the key derivation
 * takes seconds on a phone and minutes on slow ones; it runs on [work], never on the main thread), `SaveOutcome.notes`.
 *
 * @param backup owner-only: the detailed backup port (`AppGraph.ownerBackup`, never null where this screen is shown).
 * @param work where the save runs (the key derivation is CPU work): the background pool; tests pass their own.
 * @param passphrase the generated passphrase and the choice; one per screen.
 */
class BackupSaveViewModel(
    private val backup: DetailedOwnerBackup,
    private val work: CoroutineContext = Dispatchers.Default,
    private val passphrase: SavePassphrase = SavePassphrase(),
) : ViewModel() {
    /** The owner's own passphrase, in a field the keyboard does not learn (`SecretTextField(state = …)`, rule 27). */
    val ownPassphrase: TextFieldState = TextFieldState()

    /** The owner's own passphrase once more, so a typing slip in a hidden field cannot lock the file for ever. */
    val ownPassphraseAgain: TextFieldState = TextFieldState()

    private val state = MutableStateFlow(BackupSaveUiState(generated = passphrase.generated))

    val uiState: StateFlow<BackupSaveUiState> = state.asStateFlow()

    private var job: Job? = null

    /** "Use my own" (true) or the generated passphrase (false). */
    fun useOwn(own: Boolean) {
        if (state.value.working) return
        passphrase.useOwn = own
        state.update { it.copy(useOwn = own, failure = null, saved = null) }
    }

    /** Another generated passphrase; the owner writes the new one down. */
    fun newPassphrase() {
        if (state.value.working) return
        passphrase.regenerate()
        state.update { it.copy(generated = passphrase.generated, wroteItDown = false, failure = null, saved = null) }
    }

    fun setWroteItDown(wrote: Boolean) {
        state.update { it.copy(wroteItDown = wrote) }
    }

    /**
     * Why the own passphrase cannot be used yet, in the owner's words (`Passphrases.problemForSave`), or null; null too
     * while nothing is typed, so the screen does not scold an empty field. Reads the field now (composition reads it).
     */
    fun ownProblem(): String? {
        val typed = ownPassphrase.text
        if (typed.isEmpty()) return null
        return passphrase.problem(typed)
    }

    /** "The two passphrases are not the same.", once the second field holds something, or null. */
    fun againProblem(): String? {
        val again = ownPassphraseAgain.text
        if (again.isEmpty()) return null
        return if (Passphrases.normalise(again) == Passphrases.normalise(ownPassphrase.text)) null else NOT_THE_SAME
    }

    /** True when Save can start: a passphrase the rules accept, written down or typed twice alike. Reads the fields now. */
    fun canSave(): Boolean {
        val current = state.value
        if (current.working) return false
        return if (current.useOwn) {
            ownPassphrase.text.isNotEmpty() && passphrase.problem(ownPassphrase.text) == null &&
                ownPassphraseAgain.text.isNotEmpty() && againProblem() == null
        } else {
            current.generated != null && current.wroteItDown
        }
    }

    /**
     * Saves the backup into [chosen], the file the owner picked (SEC-W3-1). That may be a new, empty file the picker just
     * made, or a file that already exists: Android's picker lets the owner tap one and asks "Overwrite …?". So the whole
     * backup is first written into [staging], a private file of the app. Only once it is complete and checked (its size
     * as the backup reports it, and the start of a backup file) is it put into [chosen]. A save that is stopped, refused
     * or fails before that leaves [chosen] exactly as it was; only a file that was empty when the save started (the one
     * the picker made) is removed again.
     */
    fun save(chosen: ChosenBackupFile, staging: File) {
        if (!canSave()) return
        val choice = passphrase.forSave(if (passphrase.useOwn) ownPassphrase.text else "") ?: return
        state.update {
            it.copy(
                working = true, cancellable = true, stopAsked = false, step = BackupStepUi(BackupTexts.STEP_GATHERING, null),
                failure = null, saved = null,
            )
        }
        job = viewModelScope.launch {
            var madeByPicker = false
            var replacing = false
            try {
                madeByPicker = sizeOf(chosen) == 0L
                val result = try {
                    withContext(work) {
                        backup.saveWithNotes(choice.first, choice.second, { staging.outputStream() }) { progress ->
                            state.update { it.copy(step = BackupStepUi.of(progress)) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BackupResult.Failed(BackupProblem.CannotOpenFile)
                }
                val done = (result as? BackupResult.Done)?.value
                val ready = done?.takeIf { withContext(work) { isCompleteBackup(staging, it.report.sizeBytes) } }
                if (ready == null) {
                    if (madeByPicker) withContext(NonCancellable) { removeIfStillEmpty(chosen) }
                    val problem = (result as? BackupResult.Failed)?.problem ?: BackupProblem.CannotOpenFile
                    state.update { it.ended(failure = untouched(problem.text, madeByPicker)) }
                    return@launch
                }
                // The whole backup is ready: only now is the chosen file written, and not while the owner is asked
                // whether to stop (Stop then leaves the file as it was). No Stop from here, it takes moments.
                stopOfferEnds()
                replacing = true
                val replaced = withContext(NonCancellable) {
                    try {
                        chosen.replaceWith(staging)
                        null
                    } catch (failed: ReplaceFailed) {
                        failed
                    } catch (_: Exception) {
                        ReplaceFailed(chosenUntouched = false)
                    }
                }
                if (replaced != null) {
                    if (madeByPicker) withContext(NonCancellable) { removeQuietly(chosen) }
                    val text = when {
                        madeByPicker -> NOT_WRITTEN
                        replaced.chosenUntouched -> untouched(NOT_WRITTEN, madeByPicker = false)
                        else -> "$NOT_WRITTEN $MAY_BE_DAMAGED"
                    }
                    state.update { it.ended(failure = text) }
                    return@launch
                }
                // Shown once: after a save the generated passphrase and the typed one are gone from this screen.
                forget()
                state.update {
                    it.ended(failure = null).copy(
                        generated = null,
                        saved = SavedUi(BackupTexts.counts(ready.report.counts) + ".", formatBytes(ready.report.sizeBytes), ready.notes),
                    )
                }
            } catch (cancelled: CancellationException) {
                // Stopped at any point before the ready backup went into the file: while the file's app was asked its
                // size, while the key was made, while the backup was checked, or while the owner was asked. The screen
                // is free again at once; the chosen file was never touched.
                withContext(NonCancellable) {
                    withContext(work) { deleteQuietly(staging) }
                    if (!replacing) {
                        state.update { it.ended(failure = untouched(CANCELLED, madeByPicker)) }
                        if (madeByPicker) removeIfStillEmpty(chosen)
                    }
                }
                throw cancelled
            } finally {
                withContext(NonCancellable + work) { deleteQuietly(staging) }
            }
        }
    }

    /** The owner tapped Cancel or Back while saving: "Stop saving?" is asked; the ready backup waits for the answer. */
    fun askToStop() {
        state.update { if (it.cancellable) it.copy(stopAsked = true) else it }
    }

    /** "Keep saving": the question goes and the save goes on. */
    fun keepSaving() {
        state.update { it.copy(stopAsked = false) }
    }

    /** Stops the save (the key step stops within moments); the chosen file stays as it was. */
    fun cancel() {
        if (!state.value.cancellable) return
        // The job first: clearing the question first would let a ready backup that waits for it go into the file.
        job?.cancel()
        state.update { it.copy(stopAsked = false) }
    }

    /** Waits while the owner is asked whether to stop; then Stop is no longer offered (one step, so no tap slips in). */
    private suspend fun stopOfferEnds() {
        while (true) {
            val now = state.first { !it.stopAsked }
            if (state.compareAndSet(now, now.copy(cancellable = false, step = BackupStepUi(STEP_REPLACING, null)))) return
        }
    }

    /** The owner left the screen: the passphrases go (rule 27). */
    fun forget() {
        passphrase.forget()
        listOf(ownPassphrase, ownPassphraseAgain).forEach {
            it.clearText()
            it.undoState.clearHistory()
        }
    }

    override fun onCleared() {
        job?.cancel()
        forget()
    }

    /**
     * The chosen file's size, or null when its app does not say, cannot be asked, or takes longer than [SIZE_WAIT_MS]
     * (a hung cloud drive). Asked on [work], never on the main thread. Unknown counts as "held something": never removed.
     */
    private suspend fun sizeOf(chosen: ChosenBackupFile): Long? = try {
        withTimeoutOrNull(SIZE_WAIT_MS) { withContext(work) { chosen.size() } }
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Removes the picker's new file again, but only while it is still empty: nothing the owner had is ever removed. */
    private suspend fun removeIfStillEmpty(chosen: ChosenBackupFile) {
        if (sizeOf(chosen) == 0L) removeQuietly(chosen)
    }

    private suspend fun removeQuietly(chosen: ChosenBackupFile) {
        try {
            chosen.remove()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The picker's empty file stays; nothing of the backup is in it.
        }
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    /** The state once a save has ended, saved or not: nothing is working, nothing to stop, nothing asked. */
    private fun BackupSaveUiState.ended(failure: String?): BackupSaveUiState =
        copy(working = false, cancellable = false, stopAsked = false, step = null, failure = failure)

    /** The sentence for a save that did not touch the chosen file: an earlier file is said to be as it was. */
    private fun untouched(text: String, madeByPicker: Boolean): String = if (madeByPicker) text else "$text $UNTOUCHED"

    companion object {
        const val NOT_THE_SAME: String = "The two passphrases are not the same."
        const val CANCELLED: String = "Stopped. Nothing was saved."

        /** How long the chosen file's app may take to say the file's size (a cloud drive can hang): then it is not known. */
        const val SIZE_WAIT_MS: Long = 15_000

        /** Said after a save that did not touch a file the owner chose to replace. */
        const val UNTOUCHED: String = "The file you chose is as it was."

        /** The step once the whole backup is ready and goes into the chosen file. */
        const val STEP_REPLACING: String = "Putting the backup into the file you chose"

        /** The ready backup could not be put into the chosen file. */
        const val NOT_WRITTEN: String = "The backup could not be written into the file you chose."

        /** Said when that went wrong part way into a file that held something before. */
        const val MAY_BE_DAMAGED: String = "If it held an earlier backup, that one may be damaged now. Save again under a new name."

        /**
         * True when [staged] holds a whole backup: exactly the [reportedSize] bytes the backup says it wrote, starting as
         * every backup file does.
         */
        fun isCompleteBackup(staged: File, reportedSize: Long): Boolean = try {
            hasWholeBackup(staged, reportedSize)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

        private fun hasWholeBackup(staged: File, reportedSize: Long): Boolean {
            val magic = com.aicontrolcenter.core.backup.BackupFormat.MAGIC.encodeToByteArray()
            if (!staged.isFile || staged.length() != reportedSize || reportedSize < magic.size) return false
            val start = ByteArray(magic.size)
            val read = staged.inputStream().use { input ->
                var got = 0
                while (got < start.size) {
                    val n = input.read(start, got, start.size - got)
                    if (n < 0) break
                    got += n
                }
                got
            }
            return read == magic.size && start.contentEquals(magic)
        }

        /** The name the picker suggests: "AI Control Center backup 2026-11-26.aiccbackup". */
        fun suggestedName(nowMs: Long, zone: ZoneId): String =
            "AI Control Center backup ${java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()}.${com.aicontrolcenter.core.backup.BackupFormat.FILE_EXTENSION}"
    }
}

/** One setting a restore changes, as the owner reads it. */
@Immutable
data class SettingChangeUi(val name: String, val from: String, val to: String) {
    val spoken: String get() = "$name: now $from, after the restore $to"
}

/** One value of the file a restore does not take as it is. */
@Immutable
data class KeptBackUi(val name: String, val inFile: String, val afterRestore: String) {
    val spoken: String get() = "$name: $inFile in the backup, $afterRestore after the restore"
}

/** What the opened file holds and what restoring it would do. */
@Immutable
data class RestoreSummaryUi(
    val madeOn: String,
    val what: String,
    val size: String,
    val changes: List<SettingChangeUi>,
    val keptBack: List<KeptBackUi>,
    val alwaysOff: String,
    val notes: List<String>,
    /** Why a restore cannot happen now (not a fresh app, too little room, a restore running), or null. */
    val refusal: String?,
)

/** What a finished restore says, before the app starts again. */
@Immutable
data class RestoredUi(val what: String, val notes: List<String>)

/** What the Restore screen shows. */
@Immutable
data class BackupRestoreUiState(
    val isLoading: Boolean = false,
    /** The chosen file's name, or null before one is chosen. */
    val fileName: String? = null,
    val working: Boolean = false,
    val step: BackupStepUi? = null,
    /** True while the work may be stopped (not while the records are written). */
    val cancellable: Boolean = false,
    val summary: RestoreSummaryUi? = null,
    /** An objective or a chat answer runs now: a restore waits for it. */
    val busy: String? = null,
    /** Why the last opening or restore did nothing, in the owner's words. */
    val failure: String? = null,
    /** Set once the restore is done: the app starts again next (rule 30). */
    val restored: RestoredUi? = null,
) {
    /** True when Restore can start now. */
    val canRestore: Boolean get() = summary != null && summary.refusal == null && busy == null && !working && restored == null
}

/**
 * Restoring a backup file into a fresh app (Tier B, rules 8 and 30; WP10b): the file's passphrase ([passphrase]),
 * `preview` (counts, every setting that changes, what is kept back, `BackupTexts.ALWAYS_OFF`, the notes, a refusal known
 * in advance), then `restoreWithNotes`; the screen restarts the app after it (rule 30). Refused while an objective runs or
 * a chat answer is under way ([busy]), as the backup itself refuses an app that is not fresh.
 *
 * @param directory agent names, so the Director on this phone now is shown by name, never by id.
 * @param work where the key derivation and the reading run: the background pool; tests pass their own.
 * @param busy a sentence while an objective runs or a chat answer is under way, else null.
 * @param zone the owner's zone, for the date the backup was made.
 */
class BackupRestoreViewModel(
    private val backup: DetailedOwnerBackup,
    private val directory: RegistryDirectory,
    private val work: CoroutineContext = Dispatchers.Default,
    busy: Flow<String?> = flowOf(null),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    /** The file's passphrase (`SecretTextField(state = …)`, rule 27). */
    val passphrase: TextFieldState = TextFieldState()

    private val state = MutableStateFlow(BackupRestoreUiState())

    val uiState: StateFlow<BackupRestoreUiState> = state.asStateFlow()

    private var open: (suspend () -> InputStream)? = null
    private var job: Job? = null

    init {
        viewModelScope.launch { busy.collect { sentence -> state.update { it.copy(busy = sentence) } } }
    }

    /** The owner chose a file ([open] reads it; [name] is what its app calls it, read off the main thread). */
    fun fileChosen(open: suspend () -> InputStream, name: suspend () -> String? = { null }) {
        if (state.value.working || state.value.restored != null) return
        this.open = open
        state.update { it.copy(fileName = CHOSEN, summary = null, failure = null) }
        viewModelScope.launch {
            val shown = try {
                withContext(work) { name() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (shown != null && this@BackupRestoreViewModel.open === open) state.update { it.copy(fileName = shown) }
        }
    }

    /** True when "Open the backup" can start: a file and a passphrase. Reads the field now. */
    fun canOpen(): Boolean = open != null && passphrase.text.isNotBlank() && !state.value.working && state.value.restored == null

    /** Opens the chosen file with the passphrase: checks all of it and says what a restore would do. Writes nothing. */
    fun openBackup() {
        val reader = open ?: return
        if (!canOpen()) return
        val typed = passphrase.text.toString()
        state.update { it.copy(working = true, cancellable = true, step = BackupStepUi(BackupTexts.STEP_UNLOCKING, null), summary = null, failure = null) }
        job = viewModelScope.launch {
            val result = run(cancelledText = CANCELLED_OPENING) { backup.preview(reader, typed, ::onProgress) } ?: return@launch
            when (result) {
                is BackupResult.Done -> {
                    val summary = summaryOf(result.value)
                    state.update { it.copy(working = false, step = null, summary = summary) }
                }
                is BackupResult.Failed -> failed(result.problem)
            }
        }
    }

    /** Restores the opened file (the owner confirmed). Nothing is written unless all of it is; the app then starts again. */
    fun restore() {
        val reader = open ?: return
        if (!state.value.canRestore) return
        val typed = passphrase.text.toString()
        state.update { it.copy(working = true, cancellable = true, step = BackupStepUi(BackupTexts.STEP_UNLOCKING, null), failure = null) }
        job = viewModelScope.launch {
            val result = run(cancelledText = CANCELLED_RESTORE) { backup.restoreWithNotes(reader, typed, ::onProgress) } ?: return@launch
            when (result) {
                is BackupResult.Done -> {
                    forget()
                    val outcome = result.value
                    state.update {
                        it.copy(working = false, step = null, cancellable = false, restored = RestoredUi(BackupTexts.counts(outcome.report.counts) + ".", outcome.notes))
                    }
                }
                is BackupResult.Failed -> failed(result.problem)
            }
        }
    }

    /** Stops opening or restoring while the passphrase is checked or the file is read; nothing is written. */
    fun cancel() {
        if (state.value.cancellable) job?.cancel()
    }

    /** The owner left the screen: the passphrase goes (rule 27). */
    fun forget() {
        passphrase.clearText()
        passphrase.undoState.clearHistory()
    }

    override fun onCleared() {
        job?.cancel()
        forget()
    }

    private fun onProgress(progress: BackupProgress) {
        // Once the records are written a stop would leave nothing to stop for: Cancel goes away.
        state.update { it.copy(step = BackupStepUi.of(progress), cancellable = progress.step != BackupTexts.STEP_RESTORING) }
    }

    private suspend fun <T> run(cancelledText: String, block: suspend () -> BackupResult<T>): BackupResult<T>? = try {
        withContext(work) { block() }
    } catch (cancelled: CancellationException) {
        state.update { it.copy(working = false, step = null, cancellable = false, failure = cancelledText) }
        throw cancelled
    } catch (_: Exception) {
        BackupResult.Failed(BackupProblem.CannotOpenFile)
    }

    private fun failed(problem: BackupProblem) {
        // A wrong passphrase is typed again from the start: the field hides what is in it.
        if (problem == BackupProblem.WrongPassphrase) forget()
        state.update { it.copy(working = false, step = null, cancellable = false, failure = problem.text) }
    }

    private suspend fun summaryOf(preview: RestorePreview): RestoreSummaryUi {
        val here: Map<AgentId, String> = try {
            directory.snapshot().agents.associate { it.profile.id to it.profile.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val summary = preview.summary
        return RestoreSummaryUi(
            madeOn = "Made on ${madeOnFormat.format(java.time.Instant.ofEpochMilli(summary.createdAt).atZone(zone()))} by version ${summary.appVersion} of the app.",
            what = BackupTexts.counts(summary.counts) + ".",
            size = formatBytes(summary.sizeBytes),
            changes = summary.settingChanges.map { change ->
                // The Director is said by name, never by id: the agent on this phone now is named from the directory.
                val from = if (change.agentFrom != null && change.from == RestoreSettings.UNKNOWN_AGENT) {
                    here[change.agentFrom]?.let { "$it (on this phone now)" } ?: change.from
                } else {
                    change.from
                }
                SettingChangeUi(change.name, from, change.to)
            },
            keptBack = preview.keptBack.map { KeptBackUi(it.name, it.inFile, it.afterRestore) },
            alwaysOff = BackupTexts.ALWAYS_OFF,
            notes = preview.notes,
            refusal = preview.refusal?.text,
        )
    }

    companion object {
        const val CHOSEN: String = "The chosen file"

        private val madeOnFormat = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", java.util.Locale.ENGLISH)
        const val CANCELLED_OPENING: String = "Stopped. The file was not opened."
        const val CANCELLED_RESTORE: String = "Stopped. Nothing was restored."

        /** The sentence while something runs (core's `BackupProblem.Busy` wording). */
        fun busyText(objectiveRunning: Boolean, chatAnswering: Boolean): String? = when {
            objectiveRunning -> BackupProblem.Busy("An objective is running.").text
            chatAnswering -> BackupProblem.Busy("An agent is answering in a chat.").text
            else -> null
        }
    }
}
