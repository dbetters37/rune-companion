package com.aicontrolcenter.app.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.FileContent
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderFiles
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.framework.operator.AuthorisedApps
import com.aicontrolcenter.framework.operator.FolderDirectory

/*
 * What the permission broker, the capability requester, the task hooks and the tools are given
 * instead of the full stores. Each is a separate object that implements the agent-side interface
 * only, so nothing holding one can be cast to the owner's side (deciding a request, storing a
 * grant, adding or removing a folder or an app, taking or releasing a folder's access), even
 * where one implementation serves both sides: Room's request store also decides, and the
 * device's folder gateway also takes and releases access.
 *
 * `OperatorDependencyTest` (app unit tests) walks the wired graph and allows a full store only
 * inside these views.
 */

/** Raise, watch and close requests; never answer one. */
class RequestQueueView(store: OwnerRequestQueue) : OwnerRequestQueue by store

/** Find, use up and narrow grants; never store or give one. */
class GrantLookupView(store: GrantLookup) : GrantLookup by store

// The screen operator's view is [ScreenSessions], which also follows who operates which app.

/** The owner's folders, read only: never adding, changing or removing one. */
class FolderDirectoryView(private val store: SharedFolderRepository) : FolderDirectory {
    override suspend fun all(): List<SharedFolder> = store.getAll()

    override suspend fun get(id: FolderId): SharedFolder? = store.get(id)
}

/** The authorised apps, read only: never authorising or withdrawing one. */
class AuthorisedAppsView(private val store: OperatorAppRepository) : AuthorisedApps {
    override suspend fun get(packageName: String): OperatorApp? = store.get(packageName)
}

/** Files inside the owner's folders; never taking or releasing Android's access to a folder. */
class SharedFolderFilesView(store: SharedFolders) : SharedFolderFiles by store

/**
 * The owner's folders as folder pick-up reads them (security review of wave 1, F11): a folder's record, the files
 * directly inside it, and reading one; never writing, deleting, adding or removing anything.
 */
class PickUpFoldersView(private val store: SharedFolderRepository, private val files: SharedFolderFiles) : PickUpFolders {
    override suspend fun folder(id: FolderId): SharedFolder? = store.get(id)

    override suspend fun all(): List<SharedFolder> = store.getAll()

    override suspend fun files(folder: FolderId): DocResult<List<DocumentEntry>> = files.list(folder, RelativePath.ROOT)

    override suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent> = files.read(folder, path, maxBytes)
}
