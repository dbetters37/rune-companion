package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.AppActions
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.EmailDraft
import com.aicontrolcenter.core.operator.FileContent
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.LinkPolicy
import com.aicontrolcenter.core.operator.MediaAccess
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaItem
import com.aicontrolcenter.core.operator.MediaLibrary
import com.aicontrolcenter.core.operator.MediaQuery
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.operator.TermuxRunner
import com.aicontrolcenter.core.operator.WebReadLimits
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.operator.WebAddressPolicy
import com.aicontrolcenter.core.operator.WebReader
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.operator.WorkspaceRoot
import com.aicontrolcenter.core.registry.InstalledAppInfo
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A workspace in a directory the test chooses, by default a fresh temporary one. */
class FakeWorkspaceRoot(private val dir: File = Files.createTempDirectory("aicc-workspace").toFile()) : WorkspaceRoot {
    override fun directory(): File = dir.also { it.mkdirs() }
}

/**
 * Shared folders kept in memory, keyed by tree URI. Tool-facing calls look the folder up in
 * [folders] on every call, as the real gateway does. Tests make a tree pickable with [pickable],
 * fill it with [putFile], and read results back with [fileText] and [exists]. Every call is
 * recorded in [calls] as "<operation> <tree> <path>"; [failNext] makes the next one fail.
 */
class FakeSharedFolders(
    /** The stored folders, read on every call. */
    val folders: SharedFolderRepository = InMemorySharedFolderRepository(),
    private val clock: Clock = Clock { 1_700_000_000_000L },
) : SharedFolders {
    private class Node(val isFolder: Boolean, val bytes: ByteArray = ByteArray(0), val mimeType: String? = null, val modifiedAt: Long)

    private val lock = Any()
    private val trees = HashMap<String, MutableMap<String, Node>>()

    /** Trees the system picker can return: tree URI → display name. */
    val pickable = ConcurrentHashMap<String, String>()

    /** Tree URIs with persisted access. */
    val granted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val calls = CopyOnWriteArrayList<String>()

    /** When set, the next operation on a folder returns it instead, then it is cleared. */
    @Volatile var failNext: DocResult<Nothing>? = null

    /** Makes [treeUri] pickable and granted, as if the owner had picked it. */
    fun grant(treeUri: String, displayName: String) {
        pickable[treeUri] = displayName
        granted += treeUri
        synchronized(lock) { trees.getOrPut(treeUri) { HashMap() } }
    }

    fun putFile(treeUri: String, path: String, content: String, mimeType: String = "text/plain") =
        putFile(treeUri, path, content.toByteArray(), mimeType)

    fun putFile(treeUri: String, path: String, bytes: ByteArray, mimeType: String? = null) = synchronized(lock) {
        val tree = trees.getOrPut(treeUri) { HashMap() }
        val p = RelativePath.of(path)
        makeParents(tree, p)
        tree[p.value] = Node(false, bytes, mimeType, clock.now())
    }

    fun fileText(treeUri: String, path: String): String? = synchronized(lock) {
        trees[treeUri]?.get(RelativePath.of(path).value)?.takeIf { !it.isFolder }?.bytes?.decodeToString()
    }

    fun exists(treeUri: String, path: String): Boolean = synchronized(lock) { trees[treeUri]?.containsKey(RelativePath.of(path).value) == true }

    override suspend fun takeAccess(treeUri: String): FolderAccess {
        calls += "takeAccess $treeUri"
        val name = pickable[treeUri] ?: return FolderAccess.Refused("That folder cannot be used.")
        granted += treeUri
        synchronized(lock) { trees.getOrPut(treeUri) { HashMap() } }
        return FolderAccess.Granted(name)
    }

    override suspend fun releaseAccess(folder: SharedFolder) {
        calls += "releaseAccess ${folder.treeUri}"
        granted -= folder.treeUri
    }

    override suspend fun hasAccess(folder: SharedFolder): Boolean = folder.treeUri in granted

    override suspend fun list(folder: FolderId, path: RelativePath) = op("list", folder, path) { tree ->
        val node = nodeAt(tree, path) ?: return@op DocResult.NotFound(path.value)
        if (!node.isFolder) return@op DocResult.Failed("${path.value} is a file, not a folder.")
        val children = tree.keys.map { RelativePath.of(it) }.filter { !it.isRoot && it.parent == path }
            .map { entry(tree, it) }.sortedWith(compareBy({ !it.isFolder }, { it.name }))
        DocResult.Ok(children)
    }

    override suspend fun stat(folder: FolderId, path: RelativePath) = op("stat", folder, path) { tree ->
        if (nodeAt(tree, path) == null) DocResult.NotFound(path.value) else DocResult.Ok(entry(tree, path))
    }

    override suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long) = op("read", folder, path) { tree ->
        val node = nodeAt(tree, path)?.takeIf { !it.isFolder } ?: return@op DocResult.NotFound(path.value)
        DocResult.Ok(FileContent(node.bytes.copyOf(minOf(maxBytes, node.bytes.size.toLong()).toInt()), node.bytes.size.toLong()))
    }

    override suspend fun write(folder: FolderId, path: RelativePath, bytes: ByteArray, mimeType: String?, overwrite: Boolean) =
        op("write", folder, path, changes = true) { tree -> writeIn(tree, path, bytes, mimeType, overwrite) }

    override suspend fun makeFolder(folder: FolderId, path: RelativePath) = op("makeFolder", folder, path, changes = true) { tree ->
        val node = nodeAt(tree, path)
        when {
            node?.isFolder == true -> DocResult.Ok(entry(tree, path))
            node != null -> DocResult.AlreadyExists(path.value)
            else -> {
                makeParents(tree, path)
                tree[path.value] = Node(true, modifiedAt = clock.now())
                DocResult.Ok(entry(tree, path))
            }
        }
    }

    override suspend fun rename(folder: FolderId, path: RelativePath, newName: String) = op("rename", folder, path, changes = true) { tree ->
        if (path.isRoot) return@op DocResult.NotAllowed("The folder itself cannot be renamed.")
        nodeAt(tree, path) ?: return@op DocResult.NotFound(path.value)
        val target = try {
            path.parent!!.child(newName)
        } catch (e: IllegalArgumentException) {
            return@op DocResult.Failed("\"$newName\" is not a valid name.")
        }
        if (nodeAt(tree, target) != null) return@op DocResult.AlreadyExists(target.value)
        val moved = tree.keys.filter { it == path.value || it.startsWith(path.value + "/") }
        for (key in moved) tree[target.value + key.removePrefix(path.value)] = tree.remove(key)!!
        DocResult.Ok(entry(tree, target))
    }

    override suspend fun delete(folder: FolderId, path: RelativePath) = op("delete", folder, path, changes = true) { tree ->
        if (path.isRoot) return@op DocResult.NotAllowed("The folder itself cannot be deleted.")
        nodeAt(tree, path) ?: return@op DocResult.NotFound(path.value)
        tree.keys.removeAll { it == path.value || it.startsWith(path.value + "/") }
        DocResult.Ok(Unit)
    }

    override suspend fun copyToWorkspace(folder: FolderId, path: RelativePath, target: WorkspaceFile, maxBytes: Long) =
        op("copyToWorkspace", folder, path) { tree ->
            val node = nodeAt(tree, path)?.takeIf { !it.isFolder } ?: return@op DocResult.NotFound(path.value)
            if (node.bytes.size > maxBytes) return@op DocResult.TooLarge(node.bytes.size.toLong(), maxBytes)
            if (target.file.exists()) return@op DocResult.AlreadyExists(target.path.value)
            target.file.parentFile?.mkdirs()
            target.file.writeBytes(node.bytes)
            DocResult.Ok(node.bytes.size.toLong())
        }

    override suspend fun copyFromWorkspace(source: WorkspaceFile, folder: FolderId, path: RelativePath, overwrite: Boolean) =
        op("copyFromWorkspace", folder, path, changes = true) { tree ->
            if (!source.file.isFile) DocResult.NotFound(source.path.value) else writeIn(tree, path, source.file.readBytes(), null, overwrite)
        }

    private fun writeIn(tree: MutableMap<String, Node>, path: RelativePath, bytes: ByteArray, mimeType: String?, overwrite: Boolean): DocResult<DocumentEntry> {
        if (path.isRoot) return DocResult.NotAllowed("A file needs a name.")
        val existing = nodeAt(tree, path)
        if (existing != null && (existing.isFolder || !overwrite)) return DocResult.AlreadyExists(path.value)
        if (!makeParents(tree, path)) return DocResult.AlreadyExists(path.parent!!.value)
        tree[path.value] = Node(false, bytes.copyOf(), mimeType, clock.now())
        return DocResult.Ok(entry(tree, path))
    }

    private suspend fun <T> op(name: String, id: FolderId, path: RelativePath, changes: Boolean = false, block: (MutableMap<String, Node>) -> DocResult<T>): DocResult<T> {
        val folder = folders.get(id)
        calls += "$name ${folder?.treeUri ?: id.value} ${path.value}"
        failNext?.let { failNext = null; return it }
        if (folder == null) return DocResult.NotAllowed("That folder was removed.")
        if (folder.treeUri !in granted) return DocResult.NotAllowed("Access to ${folder.displayName} was withdrawn.")
        if (changes && folder.readOnly) return DocResult.NotAllowed("${folder.displayName} is read-only.")
        return synchronized(lock) { block(trees.getOrPut(folder.treeUri) { HashMap() }) }
    }

    private fun nodeAt(tree: Map<String, Node>, path: RelativePath): Node? =
        if (path.isRoot) Node(true, modifiedAt = 0) else tree[path.value]

    /** Creates missing parent folders; false if a parent is a file. */
    private fun makeParents(tree: MutableMap<String, Node>, path: RelativePath): Boolean {
        var parent = path.parent
        val missing = ArrayList<RelativePath>()
        while (parent != null && !parent.isRoot) {
            val node = tree[parent.value]
            if (node != null && !node.isFolder) return false
            if (node == null) missing += parent
            parent = parent.parent
        }
        missing.forEach { tree[it.value] = Node(true, modifiedAt = clock.now()) }
        return true
    }

    private fun entry(tree: Map<String, Node>, path: RelativePath): DocumentEntry {
        val node = nodeAt(tree, path)!!
        return DocumentEntry(path, node.isFolder, if (node.isFolder) null else node.bytes.size.toLong(), node.mimeType, node.modifiedAt)
    }
}

/** A media library with the items a test puts in [items] and the bytes in [contents]. */
class FakeMediaLibrary(@Volatile var mediaAccess: MediaAccess = MediaAccess.FULL) : MediaLibrary {
    val items = CopyOnWriteArrayList<MediaItem>()
    val contents = ConcurrentHashMap<MediaId, ByteArray>()
    val queries = CopyOnWriteArrayList<MediaQuery>()
    val copies = CopyOnWriteArrayList<Pair<MediaId, WorkspaceFile>>()

    override suspend fun access(): MediaAccess = mediaAccess

    override suspend fun list(query: MediaQuery): DocResult<List<MediaItem>> {
        queries += query
        if (mediaAccess == MediaAccess.NONE) return DocResult.NotAllowed("Photo and video access is off.")
        val after = query.takenAfter
        val before = query.takenBefore
        return DocResult.Ok(
            items.filter { it.kind in query.kinds }
                .filter { after == null || (it.takenAt ?: Long.MIN_VALUE) > after }
                .filter { before == null || (it.takenAt ?: Long.MAX_VALUE) < before }
                .sortedWith(compareByDescending<MediaItem> { it.takenAt ?: Long.MIN_VALUE }.thenBy { it.id.value })
                .take(query.limit),
        )
    }

    override suspend fun copyToWorkspace(id: MediaId, target: WorkspaceFile, maxBytes: Long): DocResult<Long> {
        copies += id to target
        if (mediaAccess == MediaAccess.NONE) return DocResult.NotAllowed("Photo and video access is off.")
        val bytes = contents[id] ?: return DocResult.NotFound(id.value)
        if (bytes.size > maxBytes) return DocResult.TooLarge(bytes.size.toLong(), maxBytes)
        if (target.file.exists()) return DocResult.AlreadyExists(target.path.value)
        target.file.parentFile?.mkdirs()
        target.file.writeBytes(bytes)
        return DocResult.Ok(bytes.size.toLong())
    }
}

/** One call to [FakeAppActions]: one subtype per method, holding its arguments. */
sealed interface AppCall {
    data class Open(val packageName: String) : AppCall
    data class OpenLink(val link: String) : AppCall
    data class OpenInBrowser(val url: String) : AppCall
    data class ShareText(val text: String, val subject: String?, val packageName: String?) : AppCall
    data class ShareFile(val file: WorkspaceFile, val mimeType: String, val packageName: String?) : AppCall
    data class ComposeEmail(val draft: EmailDraft) : AppCall
    data class ComposeSms(val number: String, val body: String) : AppCall
    data class Dial(val number: String) : AppCall
    data class Uninstall(val packageName: String) : AppCall
    data class Install(val apk: WorkspaceFile) : AppCall
}

/**
 * Records hand-overs instead of starting activities. Apps in [installed] can be opened, shared
 * to and removed; links that [LinkPolicy] allows and whose scheme is in [handledSchemes] open.
 * [inBackground] makes every start [AppActionResult.Deferred]; [installOutcome] and
 * [uninstallOutcome] choose what Android's confirmation ends in; [nextResult] overrides the
 * next call's result.
 */
class FakeAppActions(var ownPackage: String = "com.aicontrolcenter.app") : AppActions {
    val installed = CopyOnWriteArrayList<InstalledAppInfo>()
    val handledSchemes: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("http", "https", "geo", "market")) }

    /** Whether "Install unknown apps" is on for this app. */
    @Volatile var canInstallPackages: Boolean = true

    /** Android would block starting activities now (this app is in the background). */
    @Volatile var inBackground: Boolean = false

    /** How an install ends once started; an [AppActionResult.Installed] package is added to [installed]. */
    @Volatile var installOutcome: AppActionResult = AppActionResult.Installed("com.example.installed")

    /** How an uninstall ends once started; null means [AppActionResult.Uninstalled], and the app leaves [installed]. */
    @Volatile var uninstallOutcome: AppActionResult? = null

    @Volatile var nextResult: AppActionResult? = null
    val calls = CopyOnWriteArrayList<AppCall>()

    private fun isInstalled(packageName: String) = installed.any { it.packageName == packageName }

    private fun isProtected(packageName: String) = ConsequentialControls.isProtectedPackage(packageName, ownPackage)

    private fun record(call: AppCall, default: () -> AppActionResult): AppActionResult {
        calls += call
        nextResult?.let { nextResult = null; return it }
        return default()
    }

    /** [AppActionResult.Started], or [AppActionResult.Deferred] while [inBackground]. */
    private fun start(): AppActionResult =
        if (inBackground) AppActionResult.Deferred("Android blocks opening it from the background; the owner was sent a \"Tap to open\" notification.")
        else AppActionResult.Started

    private fun shareTarget(packageName: String?): AppActionResult =
        if (packageName != null && !isInstalled(packageName)) AppActionResult.NotFound(packageName) else start()

    override suspend fun listLaunchable(): List<InstalledAppInfo> = installed.sortedBy { it.label.lowercase() }

    override suspend fun open(packageName: String) = record(AppCall.Open(packageName)) {
        when {
            isProtected(packageName) -> AppActionResult.Refused("$packageName is not opened by agents.")
            isInstalled(packageName) -> start()
            else -> AppActionResult.NotFound(packageName)
        }
    }

    override suspend fun openLink(link: String) = record(AppCall.OpenLink(link)) {
        when (val check = LinkPolicy.check(link, ownPackage)) {
            is LinkPolicy.Check.Refused -> AppActionResult.Refused(check.reason)
            is LinkPolicy.Check.Allowed -> if (check.scheme in handledSchemes) start() else AppActionResult.NotFound("an app for ${check.scheme}: links")
        }
    }

    /** The apps [linkHandlers] names, by scheme; a scheme in [handledSchemes] without an entry is opened by one "Example <scheme> app". */
    val linkApps: MutableMap<String, List<InstalledAppInfo>> = ConcurrentHashMap()

    override suspend fun linkHandlers(link: String): List<InstalledAppInfo> {
        val check = LinkPolicy.check(link, ownPackage) as? LinkPolicy.Check.Allowed ?: return emptyList()
        return linkApps[check.scheme]
            ?: if (check.scheme in handledSchemes) listOf(InstalledAppInfo("com.example.${check.scheme}", "Example ${check.scheme} app")) else emptyList()
    }

    override suspend fun openInBrowser(url: String) = record(AppCall.OpenInBrowser(url)) {
        val check = LinkPolicy.check(url, ownPackage)
        if (check is LinkPolicy.Check.Allowed && (check.scheme == "http" || check.scheme == "https")) start()
        else AppActionResult.Refused("Only http and https addresses open in the browser.")
    }

    override suspend fun shareText(text: String, subject: String?, packageName: String?) =
        record(AppCall.ShareText(text, subject, packageName)) { shareTarget(packageName) }

    override suspend fun shareFile(file: WorkspaceFile, mimeType: String, packageName: String?) = record(AppCall.ShareFile(file, mimeType, packageName)) {
        if (!file.file.isFile) AppActionResult.NotFound(file.path.value) else shareTarget(packageName)
    }

    override suspend fun composeEmail(draft: EmailDraft) = record(AppCall.ComposeEmail(draft)) { start() }

    override suspend fun composeSms(number: String, body: String) = record(AppCall.ComposeSms(number, body)) { start() }

    override suspend fun dial(number: String) = record(AppCall.Dial(number)) { start() }

    override suspend fun uninstall(packageName: String, waitMs: Long) = record(AppCall.Uninstall(packageName)) {
        when {
            isProtected(packageName) -> AppActionResult.Refused("$packageName cannot be removed by an agent.")
            !isInstalled(packageName) -> AppActionResult.NotFound(packageName)
            else -> (uninstallOutcome ?: AppActionResult.Uninstalled(packageName)).also { outcome ->
                if (outcome is AppActionResult.Uninstalled) installed.removeAll { it.packageName == packageName }
            }
        }
    }

    override suspend fun install(apk: WorkspaceFile, waitMs: Long) = record(AppCall.Install(apk)) {
        when {
            !apk.file.isFile -> AppActionResult.NotFound(apk.path.value)
            !canInstallPackages -> AppActionResult.NeedsSpecialAccess(DeviceAccess.INSTALL_UNKNOWN_APPS, "Allow this app to install unknown apps first.")
            inBackground -> start()
            else -> installOutcome.also { outcome ->
                if (outcome is AppActionResult.Installed) installed += InstalledAppInfo(outcome.packageName, outcome.packageName)
            }
        }
    }
}

/** Serves [pages] by exact URL; anything else fails as unreachable. Requests are recorded. */
class FakeWebReader : WebReader {
    val pages = ConcurrentHashMap<String, WebReadResult>()
    val requests = CopyOnWriteArrayList<Pair<String, WebReadLimits>>()

    fun page(url: String, body: String, contentType: String? = "text/html; charset=utf-8", status: Int = 200) {
        pages[url] = WebReadResult.Page(url, status, contentType, body, truncated = false, hostsContacted = listOfNotNull(hostOf(url)))
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    override suspend fun read(url: String, limits: WebReadLimits): WebReadResult {
        requests += url to limits
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return WebReadResult.Refused("Only http and https pages can be read.")
        val host = hostOf(url)?.removePrefix("[")?.removeSuffix("]") ?: return WebReadResult.Refused("The address has no host.")
        // Literal addresses only: a fake never resolves names, but "localhost" is this device.
        if (host.equals("localhost", ignoreCase = true)) return WebReadResult.Refused("Pages on this device (loopback) are not read: only public web addresses are.")
        if (host.all { it.isDigit() || it == '.' } || ':' in host) {
            WebAddressPolicy.refusal(InetAddress.getByName(host))?.let { return WebReadResult.Refused(it) }
        }
        val result = pages[url] ?: return WebReadResult.Failed("Could not connect to ${url.take(80)}.")
        if (result !is WebReadResult.Page) return result
        val bytes = result.body.toByteArray()
        if (bytes.size <= limits.maxBytes) return result
        return result.copy(body = bytes.copyOf(limits.maxBytes.toInt()).decodeToString().trimEnd('�'), truncated = true)
    }
}

/** One command given to a fake runner. */
data class CommandRun(val command: String, val workingDirectory: WorkspaceFile?, val limits: CommandLimits)

/** Answers every command with [respond] (exit 0, no output by default) and records it. */
class FakeShellRunner(
    @Volatile var respond: (command: String, workingDirectory: WorkspaceFile) -> CommandOutcome = { _, _ -> CommandOutcome.Finished(0, "", "", false, 1) },
) : ShellRunner {
    val runs = CopyOnWriteArrayList<CommandRun>()

    override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome {
        runs += CommandRun(command, workingDirectory, limits)
        return respond(command, workingDirectory)
    }
}

/** Termux that is [availability] and answers with [respond]. Commands are recorded only when they would reach Termux. */
class FakeTermuxRunner(
    @Volatile var availability: TermuxAvailability = TermuxAvailability.READY,
    @Volatile var respond: (command: String) -> CommandOutcome = { CommandOutcome.Finished(0, "", "", false, 1) },
) : TermuxRunner {
    val runs = CopyOnWriteArrayList<CommandRun>()

    override suspend fun availability(): TermuxAvailability = availability

    override suspend fun run(command: String, limits: CommandLimits): CommandOutcome = when (availability) {
        TermuxAvailability.NOT_INSTALLED -> CommandOutcome.Unavailable("Termux is not installed.", DeviceAccess.TERMUX_RUN_COMMAND)
        TermuxAvailability.PERMISSION_MISSING -> CommandOutcome.Unavailable("This app may not run commands in Termux yet.", DeviceAccess.TERMUX_RUN_COMMAND)
        TermuxAvailability.READY -> {
            runs += CommandRun(command, null, limits)
            respond(command)
        }
    }
}
