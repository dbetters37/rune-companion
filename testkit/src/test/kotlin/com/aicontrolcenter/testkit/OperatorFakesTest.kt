package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.MediaAccess
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaItem
import com.aicontrolcenter.core.operator.MediaKind
import com.aicontrolcenter.core.operator.MediaQuery
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.operator.WebReadLimits
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.registry.InstalledAppInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorFakesTest {
    private val notes = FakeScreen(
        "com.example.notes", "Notes",
        listOf(
            FakeControl("EditText", hint = "Title", resourceId = "com.example.notes:id/title", editable = true),
            FakeControl("Button", text = "Send"),
            FakeControl("EditText", hint = "PIN", password = true, editable = true),
            FakeControl("Button", text = "Next"),
        ),
    )

    private fun <T> ok(result: ScreenResult<T>): T = assertIs<ScreenResult.Ok<T>>(result).value

    private fun workspaceFile(path: String): WorkspaceFile =
        (WorkspaceFile.resolve(FakeWorkspaceRoot().directory(), RelativePath.of(path)) as DocResult.Ok).value

    @Test
    fun `screen reads number visible elements and withhold sensitive ones`() = runTest {
        val screen = FakeScreenOperator(screen = notes)
        val read: ScreenSnapshot = ok(screen.read("Operator", "com.example.notes"))
        assertEquals(listOf(1, 2, 3), read.elements.map { it.number })
        assertEquals(1, read.withheld)
        assertTrue(read.elements.none { it.hint == "PIN" })
        val send: ScreenElement = ok(screen.find("com.example.notes", ScreenTarget.Numbered(read.id, 2)))
        assertEquals("Send", send.text)
        assertIs<ScreenResult.Ambiguous>(FakeScreenOperator(screen = notes.copy(controls = notes.controls + FakeControl(text = "Send"))).find("com.example.notes", ScreenTarget.ExactText("Send")))
    }

    @Test
    fun `screen acts only on the expected, unchanged, unprotected, authorised window`() = runTest {
        val screen = FakeScreenOperator(screen = notes)
        val first = ok(screen.read("Operator", "com.example.notes"))
        val next: ScreenElement = ok(screen.find("com.example.notes", ScreenTarget.ExactText("Next")))
        assertEquals(ScreenResult.Ok(Unit), screen.tap("Operator", next))
        screen.screen = notes.copy(controls = listOf(FakeControl(text = "Pay")))
        assertEquals(ScreenResult.Stale, screen.tap("Operator", next))
        assertEquals(ScreenResult.Stale, screen.find("com.example.notes", ScreenTarget.Numbered(first.id, 3)))
        assertEquals(ScreenResult.Stale, screen.find("com.example.notes", ScreenTarget.Numbered(999, 1)))
        screen.screen = FakeScreen("com.other.app", "Secret chat with Sam", listOf(FakeControl(text = "Private")))
        assertEquals(ScreenResult.NotAuthorised("com.other.app"), screen.read("Operator", "com.other.app"))
        assertEquals(ScreenResult.NotAuthorised("com.other.app"), screen.find("com.other.app", ScreenTarget.ExactText("Private")))
        assertNull(screen.activeWindow()?.title, "an unauthorised app's title is not read")
        assertIs<ScreenResult.WrongWindow>(screen.read("Operator", "com.example.notes"))
        screen.screen = FakeScreen("com.android.settings")
        assertEquals(ScreenResult.Protected("com.android.settings"), screen.read("Operator", "com.android.settings"))
        screen.authorised += "com.aicontrolcenter.app"
        screen.screen = FakeScreen("com.aicontrolcenter.app")
        assertEquals(ScreenResult.Protected("com.aicontrolcenter.app"), screen.globalAction("Operator", "com.aicontrolcenter.app", GlobalAction.BACK))
        screen.extraProtected += "com.oem.security"
        screen.authorised += "com.oem.security"
        screen.screen = FakeScreen("com.oem.security")
        assertEquals(ScreenResult.Protected("com.oem.security"), screen.read("Operator", "com.oem.security"))
        assertEquals(2, screen.performed.size)
    }

    @Test
    fun `a number refers to the snapshot the model read, not to a later read`() = runTest {
        val screen = FakeScreenOperator(screen = notes)
        val mine = ok(screen.read("Operator", "com.example.notes"))
        // Another read after the screen changed renumbers everything.
        screen.screen = notes.copy(controls = listOf(FakeControl(text = "Banner")) + notes.controls)
        val theirs = ok(screen.read("Helper", "com.example.notes"))
        assertEquals("Send", ok(screen.find("com.example.notes", ScreenTarget.Numbered(mine.id, 2))).text)
        assertEquals("Title", ok(screen.find("com.example.notes", ScreenTarget.Numbered(theirs.id, 2))).hint)
        repeat(FakeScreenOperator.KEPT_SNAPSHOTS) { screen.read("Helper", "com.example.notes") }
        assertEquals(ScreenResult.Stale, screen.find("com.example.notes", ScreenTarget.Numbered(mine.id, 2)))
    }

    @Test
    fun `repeated controls are told apart by where they are, and never guessed`() = runTest {
        val inbox = FakeScreen(
            "com.example.mail", "Inbox",
            listOf(FakeControl(text = "Delete", context = "Row: Invoice 42"), FakeControl(text = "Delete", context = "Row: Holiday photos")),
        )
        val screen = FakeScreenOperator(screen = inbox)
        val rows = ok(screen.read("Operator", "com.example.mail")).elements
        assertEquals(ScreenResult.Ok(Unit), screen.tap("Operator", rows[1]))
        // The approved row went away; the other row's "Delete" is not a substitute.
        screen.screen = inbox.copy(controls = listOf(inbox.controls[0]))
        assertEquals(ScreenResult.Stale, screen.tap("Operator", rows[1]))
        // Two controls that cannot be told apart: neither is used.
        screen.screen = inbox.copy(controls = listOf(FakeControl(text = "Delete"), FakeControl(text = "Delete", bounds = com.aicontrolcenter.core.operator.ScreenBounds(0, 100, 200, 160))))
        val twins = ok(screen.read("Operator", "com.example.mail")).elements
        assertEquals(ScreenResult.Stale, screen.tap("Operator", twins[0]))
        assertEquals(1, screen.performed.count { it is ScreenCall.Tap })
    }

    @Test
    fun `stop is the owner's, refuses calls at once in the documented order, and ends a wait`() = runTest {
        val screen = FakeScreenOperator(screen = notes)
        val element = ok(screen.read("Operator", "com.example.notes")).elements[1]
        val waiting = async { screen.waitFor("Operator", "com.example.notes", "Done", 60_000) }
        testScheduler.runCurrent()
        screen.stop()
        assertEquals(ScreenResult.Stopped, waiting.await())
        assertEquals(ScreenResult.Stopped, screen.read("Operator", "com.example.notes"))
        // Stop comes before the element's package is compared.
        assertEquals(ScreenResult.Stopped, screen.scroll("Operator", "com.other.app", ScrollDirection.DOWN, element))
        screen.resume()
        assertIs<ScreenResult.WrongWindow>(screen.scroll("Operator", "com.example.notes", ScrollDirection.DOWN, element.copy(packageName = "com.other.app")))
        assertIs<ScreenResult.Ok<*>>(screen.read("Operator", "com.example.notes"))
        screen.setState(ScreenServiceState.NOT_ENABLED)
        assertEquals(ScreenResult.Unavailable(ScreenServiceState.NOT_ENABLED), screen.read("Operator", "com.example.notes"))
        screen.endSession("Operator")
        assertEquals(listOf("Operator"), screen.endedSessions)
    }

    @Test
    fun `typing needs an editable, non-sensitive field, and screenshots never overwrite`() = runTest {
        val screen = FakeScreenOperator(screen = notes)
        val snapshot = ok(screen.read("Operator", "com.example.notes"))
        assertEquals(ScreenResult.Ok(Unit), screen.type("Operator", snapshot.elements[0], "Groceries", submit = false))
        assertIs<ScreenResult.NotSupported>(screen.type("Operator", snapshot.elements[1], "x", submit = false))
        assertEquals(listOf("Groceries"), screen.typed.map { it.second })
        val pin = snapshot.elements[0].copy(hint = "PIN", resourceId = null)
        assertEquals(ScreenResult.Sensitive, screen.type("Operator", pin, "1234", submit = true))
        val shot = workspaceFile("shots/notes.png")
        assertIs<ScreenResult.Ok<*>>(screen.screenshot("Operator", "com.example.notes", shot))
        assertTrue(shot.file.isFile)
        assertIs<ScreenResult.Failed>(screen.screenshot("Operator", "com.example.notes", shot))
    }

    @Test
    fun `shared folders are looked up on every call, stay inside their tree and respect read-only`() = runTest {
        val repo = InMemorySharedFolderRepository()
        val folders = FakeSharedFolders(repo)
        assertIs<FolderAccess.Refused>(folders.takeAccess("content://tree/unknown"))
        folders.pickable["content://tree/docs"] = "Documents"
        assertEquals(FolderAccess.Granted("Documents"), folders.takeAccess("content://tree/docs"))
        val docs = SharedFolder(FolderId("fld_1"), "content://tree/docs", "Documents", 1)
        repo.upsert(docs)
        folders.putFile(docs.treeUri, "reports/q1.csv", "a,b")
        assertIs<DocResult.Ok<*>>(folders.write(docs.id, RelativePath.of("notes/new.md"), "# Hi".toByteArray(), "text/markdown", overwrite = false))
        assertIs<DocResult.AlreadyExists>(folders.write(docs.id, RelativePath.of("notes/new.md"), "x".toByteArray(), null, overwrite = false))
        assertEquals(listOf("notes", "reports"), (folders.list(docs.id, RelativePath.ROOT) as DocResult.Ok).value.map { it.name })
        val partial = (folders.read(docs.id, RelativePath.of("reports/q1.csv"), 1) as DocResult.Ok).value
        assertTrue(partial.truncated)
        assertIs<DocResult.Ok<*>>(folders.rename(docs.id, RelativePath.of("reports"), "archive"))
        assertEquals("a,b", folders.fileText(docs.treeUri, "archive/q1.csv"))
        val target = workspaceFile("in/q1.csv")
        assertEquals(DocResult.Ok(3L), folders.copyToWorkspace(docs.id, RelativePath.of("archive/q1.csv"), target, 100))
        assertIs<DocResult.AlreadyExists>(folders.copyToWorkspace(docs.id, RelativePath.of("archive/q1.csv"), target, 100))
        // The owner flips read-only while a call waits: the next call sees it, whatever the caller kept.
        repo.upsert(docs.copy(readOnly = true))
        assertIs<DocResult.NotAllowed>(folders.delete(docs.id, RelativePath.of("archive")))
        repo.upsert(docs)
        assertIs<DocResult.NotAllowed>(folders.delete(docs.id, RelativePath.ROOT))
        assertIs<DocResult.Ok<*>>(folders.delete(docs.id, RelativePath.of("archive")))
        assertFalse(folders.exists(docs.treeUri, "archive/q1.csv"))
        repo.delete(docs.id)
        assertIs<DocResult.NotAllowed>(folders.list(docs.id, RelativePath.ROOT))
        repo.upsert(docs)
        folders.releaseAccess(docs)
        assertIs<DocResult.NotAllowed>(folders.list(docs.id, RelativePath.ROOT))
    }

    @Test
    fun `app actions refuse unsafe links and protected targets, and report what Android reported`() = runTest {
        val apps = FakeAppActions()
        apps.installed += InstalledAppInfo("com.example.notes", "Notes")
        assertEquals(AppActionResult.Started, apps.open("com.example.notes"))
        assertIs<AppActionResult.NotFound>(apps.open("com.missing"))
        assertIs<AppActionResult.Refused>(apps.open("com.android.settings"))
        for (link in listOf("intent://x#Intent;end", "file:///sdcard/a", " JavaScript:alert(1)", "content://x/y", "sms:123?body=hi", "https://a.b/‮x", "com.aicontrolcenter.app://approve")) {
            assertIs<AppActionResult.Refused>(apps.openLink(link), link)
        }
        assertEquals(AppActionResult.Started, apps.openLink("  https://example.com/a?b=1 "))
        assertIs<AppActionResult.Refused>(apps.uninstall("com.android.settings"))
        assertIs<AppActionResult.Refused>(apps.uninstall("com.aicontrolcenter.app"))
        apps.uninstallOutcome = AppActionResult.UserCancelled
        assertEquals(AppActionResult.UserCancelled, apps.uninstall("com.example.notes"))
        apps.uninstallOutcome = null
        assertEquals(AppActionResult.Uninstalled("com.example.notes"), apps.uninstall("com.example.notes"))
        assertTrue(apps.installed.isEmpty())
        val apk = workspaceFile("downloads/tool.apk").also { it.file.parentFile.mkdirs(); it.file.writeBytes(byteArrayOf(1)) }
        apps.canInstallPackages = false
        assertIs<AppActionResult.NeedsSpecialAccess>(apps.install(apk))
        apps.canInstallPackages = true
        assertEquals(AppActionResult.Installed("com.example.installed"), apps.install(apk))
        apps.installOutcome = AppActionResult.Failed("Incompatible with this phone.", code = 7)
        assertEquals(AppActionResult.Failed("Incompatible with this phone.", 7), apps.install(apk))
        apps.inBackground = true
        assertIs<AppActionResult.Deferred>(apps.composeSms("123", "On my way"))
        assertEquals(19, apps.calls.size)
    }

    @Test
    fun `web, media and command fakes are scriptable and record calls`() = runTest {
        val web = FakeWebReader()
        web.page("https://example.com/", "<p>héllo world</p>")
        assertIs<WebReadResult.Refused>(web.read("file:///etc/hosts", WebReadLimits()))
        for (url in listOf("http://127.0.0.1:8080/", "http://localhost/", "http://192.168.1.1/", "http://[::1]/", "http://100.64.0.1/")) {
            assertIs<WebReadResult.Refused>(web.read(url, WebReadLimits()), url)
        }
        val cut = web.read("https://example.com/", WebReadLimits(maxBytes = 5)) as WebReadResult.Page
        assertTrue(cut.truncated)
        assertEquals("<p>h", cut.body)
        assertEquals(listOf("example.com"), cut.hostsContacted)
        assertEquals(7, web.requests.size)

        val media = FakeMediaLibrary()
        media.items += MediaItem(MediaId("1"), MediaKind.IMAGE, "a.jpg", takenAt = 10)
        media.items += MediaItem(MediaId("2"), MediaKind.VIDEO, "b.mp4", takenAt = 20)
        media.contents[MediaId("1")] = byteArrayOf(1, 2)
        assertEquals(listOf("2", "1"), (media.list(MediaQuery()) as DocResult.Ok).value.map { it.id.value })
        assertEquals(listOf("1"), (media.list(MediaQuery(kinds = setOf(MediaKind.IMAGE))) as DocResult.Ok).value.map { it.id.value })
        val photo = workspaceFile("photos/a.jpg")
        assertEquals(DocResult.Ok(2L), media.copyToWorkspace(MediaId("1"), photo, 100))
        assertIs<DocResult.AlreadyExists>(media.copyToWorkspace(MediaId("1"), photo, 100))
        media.mediaAccess = MediaAccess.NONE
        assertIs<DocResult.NotAllowed>(media.list(MediaQuery()))

        val shell = FakeShellRunner { command, _ -> CommandOutcome.Finished(0, "ran: $command", "", false, 3) }
        val dir = workspaceFile("")
        assertEquals("ran: ls -la", (shell.run("ls -la", dir, CommandLimits()) as CommandOutcome.Finished).stdout)
        assertEquals("ls -la", shell.runs.single().command)

        val termux = FakeTermuxRunner(TermuxAvailability.PERMISSION_MISSING)
        assertIs<CommandOutcome.Unavailable>(termux.run("echo hi", CommandLimits()))
        assertTrue(termux.runs.isEmpty())
    }
}
