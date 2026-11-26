package com.aicontrolcenter.app.testing

import com.aicontrolcenter.app.operator.PickUpFoldersView
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.framework.pickup.PickUpRunner

/*
 * WP10c's test helpers (routines and folder pick-up screens): routines made through the owner's port, folders shared and
 * made read-only as the Folders tab does it, a memory space, and one pick-up run as the upkeep job makes it.
 */

/** A routine made through the owner's port, as the editor makes it. */
suspend fun TestApp.makeRoutine(
    title: String = "Morning news",
    objective: String = "Summarise the news about electric cars in five lines.",
    schedule: RoutineSchedule = RoutineSchedule.Daily(7 * 60),
    allowScreenActions: Boolean = false,
    approvalTimeoutMinutes: Int? = null,
): Routine {
    val draft = RoutineDraft(title, objective, ObjectiveOptions(reviewResults = false), schedule, approvalTimeoutMinutes, allowScreenActions)
    return (graph.ownerRoutines!!.create(draft) as OwnerResult.Done).value
}

/** The tree address of a folder of the phone's own storage at [path] ("Download/Inbox"). */
fun phoneFolder(path: String): String = "content://com.android.externalstorage.documents/tree/primary%3A" + path.replace("/", "%2F")

/** A folder shared through the folder picker's Downloads entry (Android does not say where it is). */
fun downloadsEntryFolder(id: String): String = "content://com.android.providers.downloads.documents/tree/" + id.replace(":", "%3A")

/** A folder of another app's storage provider (a cloud drive, a file manager). */
fun otherAppFolder(name: String): String = "content://com.example.cloud.documents/tree/root%3A$name"

/** Shares a folder as the owner does through Android's folder picker (Tools › Folders), read-only for agents when [readOnly]. */
suspend fun TestApp.shareFolder(name: String, readOnly: Boolean, treeUri: String = phoneFolder(name)): SharedFolder {
    folderAccess.grant(treeUri, name)
    val folder = (graph.ownerFolders.add(treeUri) as OwnerResult.Done).value
    return if (readOnly) graph.ownerFolders.setReadOnly(folder.id, true)!! else folder
}

/** A shared memory space the owner made. */
suspend fun TestApp.makeSpace(name: String = "Inbox notes"): KnowledgeSpace = graph.memoryService.createSpace(MemoryActor.Owner, name)

/** A pick-up rule made through the owner's port. */
suspend fun TestApp.makePickUpRule(folder: SharedFolder, space: KnowledgeSpace): PickUpRule =
    (graph.ownerPickUpRules!!.create(folder.id, space.id) as OwnerResult.Done).value

/** One pick-up run of every rule, as the upkeep job makes it (WP17's runner over the owner's folders, read only). */
suspend fun TestApp.runPickUp(): List<PickUpRunner.RuleRun> =
    PickUpRunner(pickUpRuleStore, PickUpFoldersView(sharedFolders, folderAccess), graph.memoryService, graph.settingsView, clock, { zone }, maintenanceGate).runAll()
