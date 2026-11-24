package com.aicontrolcenter.device.screen

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ActiveWindow
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.CERT
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.MAIL
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.OEM_SETTINGS
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.OWN
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccessibilityScreenOperatorTest {

    /** Notes' inbox: a list of two invoices, each with its own Delete button. */
    private fun inbox() = TestWindow(NOTES, title = "Inbox") {
        list {
            row("row 42") {
                label("Invoice 42")
                label("Due today")
                button("Delete", name = "delete 42") { viewId = "com.example.notes:id/delete" }
            }
            row("row 43") {
                label("Invoice 43")
                button("Delete", name = "delete 43") { viewId = "com.example.notes:id/delete" }
            }
        }
    }

    private suspend fun OperatorHarness.readOk(agent: String = "Scout", packageName: String = NOTES): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.read(agent, packageName)).value

    private fun <T> ScreenResult<T>.ok(): T = assertIs<ScreenResult.Ok<T>>(this).value

    private fun ScreenSnapshot.named(text: String): ScreenElement = elements.single { it.text == text || it.description == text }

    // ---- Reading ----

    @Test
    fun `a read numbers every element and says where each one is`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(inbox())

        val snapshot = h.readOk()

        assertEquals(NOTES, snapshot.packageName)
        assertEquals("Inbox", snapshot.windowTitle)
        assertEquals(0, snapshot.withheld)
        assertFalse(snapshot.truncated)
        assertEquals((1..8).toList(), snapshot.elements.map { it.number })
        assertTrue(snapshot.elements.all { it.snapshotId == snapshot.id && it.packageName == NOTES })
        assertEquals(
            listOf("RecyclerView", "LinearLayout", "TextView", "TextView", "Button", "LinearLayout", "TextView", "Button"),
            snapshot.elements.map { it.role },
        )
        val row = snapshot.elements[1]
        assertTrue(row.clickable)
        assertEquals("Invoice 42 · Due today", row.label, "a row's label merges its texts, not its own buttons")
        assertEquals("Inbox", row.context)
        assertEquals("path:/0/0", row.nodeKey)

        val deletes = snapshot.elements.filter { it.text == "Delete" }
        assertEquals(listOf("Row: Invoice 42 · Due today · Inbox", "Row: Invoice 43 · Inbox"), deletes.map { it.context })
        assertEquals(listOf("path:/0/0/2", "path:/0/1/1"), deletes.map { it.nodeKey })
        assertEquals("com.example.notes:id/delete", deletes.first().resourceId)
        assertEquals("delete", ConsequentialControls.matchedWord(deletes.first()))
        assertTrue(snapshot.elements.first().scrollable)
    }

    @Test
    fun `labels take field labels, state and action names, and unique ids become node keys`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(
            TestWindow(NOTES, title = "Compose") {
                val to = label("To", name = "to label")
                field("to") {
                    uniqueId = "field-to"
                    labeledBy = to
                }
                child("android.widget.Switch", "sync") {
                    clickable = true
                    checkable = true
                    checked = true
                    description = "Sync"
                    stateDescription = "On"
                }
                child("android.view.View", "card") {
                    clickable = true
                    actions += AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Open invoice")
                }
                label("Hidden", name = "hidden") { visible = false }
            },
        )

        val snapshot = h.readOk()

        val to = snapshot.elements.single { it.role == "EditText" }
        assertEquals("To", to.label)
        assertEquals("id:field-to", to.nodeKey)
        val sync = snapshot.named("Sync")
        assertEquals("On", sync.label)
        assertTrue(sync.checked && sync.checkable)
        assertEquals("Open invoice", snapshot.elements.single { it.role == "View" }.label)
        assertTrue(snapshot.elements.none { it.text == "Hidden" }, "invisible nodes are not read")
    }

    @Test
    fun `password fields and sensitive views are withheld, and none of their text appears anywhere`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(
            TestWindow(NOTES, title = "Sign in") {
                field("email") { text = "me@example.com" }
                field("password") {
                    password = true
                    text = "hunter2"
                    description = "Password"
                }
                row("pin row") {
                    label("PIN")
                    field("pin") {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        text = "1234"
                    }
                }
                child("android.widget.FrameLayout", "card") {
                    sensitive = true
                    description = "Card"
                    label("4111 1111 1111 1111")
                    button("Copy number")
                }
                row("account row") {
                    label("Savings")
                    child("android.widget.TextView", "balance") {
                        sensitive = true
                        description = "Balance 5,000"
                    }
                }
            },
        )

        val snapshot = h.readOk()

        assertEquals(4, snapshot.withheld)
        val everything = snapshot.elements.flatMap { listOf(it.text, it.description, it.hint, it.label, it.context) }.joinToString()
        for (secret in listOf("hunter2", "1234", "4111", "Copy number", "Card", "5,000")) assertFalse(secret in everything, "$secret leaked: $everything")
        assertEquals(listOf("PIN", "Savings"), snapshot.elements.filter { it.role == "LinearLayout" }.map { it.label })
        assertEquals(listOf("me@example.com", "PIN", "Savings"), snapshot.elements.mapNotNull { it.text })

        // Named by its description, the password field is refused, not found.
        assertEquals(ScreenResult.Sensitive, h.operator.find(NOTES, ScreenTarget.ExactText("Password")))
    }

    @Test
    fun `a read keeps at most the elements asked for and says so`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(inbox())

        val snapshot = h.operator.read("Scout", NOTES, maxElements = 3).ok()

        assertEquals(3, snapshot.elements.size)
        assertTrue(snapshot.truncated)
    }

    // ---- Finding and re-finding ----

    @Test
    fun `numbers name the snapshot the model read, even after other reads renumbered the screen`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val before = TestWindow(NOTES, title = "Draft") {
            button("Archive") { uniqueId = "archive" }
            button("Send") { uniqueId = "send" }
        }
        h.show(before)
        val first = h.readOk()
        assertEquals(2, first.named("Send").number)

        val after = TestWindow(NOTES, title = "Draft") {
            label("Offline") { uniqueId = "banner" }
            button("Archive") { uniqueId = "archive" }
            button("Send") { uniqueId = "send" }
        }
        h.show(after)
        val second = h.readOk(agent = "Writer")
        assertTrue(second.id > first.id)
        assertEquals(3, second.named("Send").number)

        val send = h.operator.find(NOTES, ScreenTarget.Numbered(first.id, 2)).ok()
        assertEquals("Send", send.text)
        assertEquals("Archive", h.operator.find(NOTES, ScreenTarget.Numbered(second.id, 2)).ok().text)

        assertEquals(ScreenResult.Ok(Unit), h.operator.tap("Scout", send))
        assertEquals(listOf(Performed("Send", AccessibilityNodeInfo.ACTION_CLICK)), after.log)
        assertEquals(ScreenResult.NotFound("element 9"), h.operator.find(NOTES, ScreenTarget.Numbered(first.id, 9)))
    }

    @Test
    fun `a control that is gone, repeated or no longer the same is stale and nothing is pressed`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(inbox())
        val delete = h.readOk().elements.first { it.text == "Delete" }

        // Gone.
        val emptied = TestWindow(NOTES, title = "Inbox") { list { row("row 43") { label("Invoice 43") } } }
        h.show(emptied)
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", delete))

        // Repeated: two controls now claim to be the same one.
        val twice = TestWindow(NOTES, title = "Dialog") {
            button("OK", name = "ok 1") { uniqueId = "ok" }
            button("OK", name = "ok 2") { uniqueId = "ok" }
        }
        h.show(TestWindow(NOTES, title = "Dialog") { button("OK") { uniqueId = "ok" } })
        val ok = h.readOk().named("OK")
        h.show(twice)
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", ok))
        assertEquals(ScreenResult.Stale, h.operator.find(NOTES, ScreenTarget.Numbered(ok.snapshotId, ok.number)))

        // The node went away between reading it and pressing it.
        val vanishing = TestWindow(NOTES, title = "Dialog") { button("OK") { uniqueId = "ok"; stale = true } }
        h.show(vanishing)
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", ok))

        // An unknown or evicted snapshot.
        h.show(inbox())
        val oldest = h.readOk()
        repeat(SnapshotStore.KEPT_SNAPSHOTS) { h.readOk() }
        assertEquals(ScreenResult.Stale, h.operator.find(NOTES, ScreenTarget.Numbered(oldest.id, 1)))
        assertEquals(ScreenResult.Stale, h.operator.find(NOTES, ScreenTarget.Numbered(42, 1)))

        assertTrue((emptied.log + twice.log + vanishing.log).isEmpty())
    }

    @Test
    fun `a label inside a control is reported as the control a tap would press`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = inbox()
        h.show(window)
        val snapshot = h.readOk()
        val invoiceLabel = snapshot.named("Invoice 42")

        val pressed = h.operator.find(NOTES, ScreenTarget.Numbered(snapshot.id, invoiceLabel.number)).ok()
        assertEquals("LinearLayout", pressed.role)
        assertEquals("Invoice 42 · Due today", pressed.label)
        assertTrue(pressed.snapshotId > snapshot.id, "renumbered in a new snapshot")

        val refused = assertIs<ScreenResult.NotSupported>(h.operator.tap("Scout", invoiceLabel))
        assertTrue("LinearLayout" in refused.reason, refused.reason)
        assertTrue(window.log.isEmpty())

        assertEquals(ScreenResult.Ok(Unit), h.operator.tap("Scout", pressed))
        assertEquals(listOf(Performed("row 42", AccessibilityNodeInfo.ACTION_CLICK)), window.log)
    }

    @Test
    fun `exact text finds one control, counts a label and its button once, and refuses ambiguity`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(
            TestWindow(NOTES, title = "Chat") {
                child("android.widget.FrameLayout", "send") {
                    clickable = true
                    description = "Send"
                    label("Send", name = "send text")
                }
            },
        )
        val send = h.operator.find(NOTES, ScreenTarget.ExactText("Send")).ok()
        assertEquals("FrameLayout", send.role)
        assertEquals(ScreenResult.NotFound("\"Reply\""), h.operator.find(NOTES, ScreenTarget.ExactText("Reply")))

        h.show(inbox())
        assertEquals(ScreenResult.Ambiguous(2), h.operator.find(NOTES, ScreenTarget.ExactText("Delete")))
    }

    // ---- The checks, in order ----

    @Test
    fun `nothing of an app that is not authorised is read`() = runTest {
        val h = OperatorHarness(this)
        h.show(TestWindow(MAIL, title = "Secret thread") { button("Reply") })
        val element = ScreenElement(1, 1, MAIL, "Button", text = "Reply", bounds = com.aicontrolcenter.core.operator.ScreenBounds(0, 0, 1, 1))

        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.read("Scout", MAIL))
        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.find(MAIL, ScreenTarget.ExactText("Reply")))
        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.tap("Scout", element))
        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.waitFor("Scout", MAIL, "Reply", 1_000))
        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.globalAction("Scout", MAIL, GlobalAction.BACK))
        assertEquals(0, h.host.windowReads, "the window list was not even looked at")

        assertEquals(ActiveWindow(MAIL, title = null), h.operator.activeWindow())
    }

    @Test
    fun `an app whose signing certificate changed, or was never recorded, is not authorised`() = runTest {
        val h = OperatorHarness(this)
        h.show(TestWindow(NOTES, title = "Inbox"))

        h.authorise(certificate = CERT)
        h.facts.certificates[NOTES] = "cd".repeat(32)
        assertEquals(ScreenResult.NotAuthorised(NOTES), h.operator.read("Scout", NOTES))

        h.facts.certificates.remove(NOTES)
        assertEquals(ScreenResult.NotAuthorised(NOTES), h.operator.read("Scout", NOTES), "a certificate that cannot be read now")

        h.apps.upsert(OperatorApp(NOTES, "Notes", 1, signingCertSha256 = null))
        h.facts.certificates[NOTES] = CERT
        assertEquals(ScreenResult.NotAuthorised(NOTES), h.operator.read("Scout", NOTES), "nothing recorded to compare with")

        h.apps.upsert(OperatorApp(NOTES, "Notes", 1, signingCertSha256 = CERT.uppercase()))
        h.readOk()

        h.apps.delete(NOTES)
        assertEquals(ScreenResult.NotAuthorised(NOTES), h.operator.read("Scout", NOTES), "withdrawn")
        assertEquals(ActiveWindow(NOTES, title = null), h.operator.activeWindow())
    }

    @Test
    fun `protected apps are refused even if listed (this app, settings, resolved handlers)`() = runTest {
        val h = OperatorHarness(this)
        for (pkg in listOf(OWN, "com.aicontrolcenter.app.debug", "com.android.settings", "com.android.permissioncontroller", OEM_SETTINGS)) {
            h.authorise(pkg)
            h.show(TestWindow(pkg, title = "Approve request") { button("Approve") })
            assertEquals(ScreenResult.Protected(pkg), h.operator.read("Scout", pkg))
            assertEquals(ScreenResult.Protected(pkg), h.operator.globalAction("Scout", pkg, GlobalAction.HOME))
            assertEquals(ActiveWindow(pkg, title = null), h.operator.activeWindow(), "no title for a protected app")
        }
        assertTrue(h.host.globalActions.isEmpty())
    }

    @Test
    fun `the checks run in the contract's order`() = runTest {
        val h = OperatorHarness(this, connected = false)
        h.control.stop()
        // 1. Unavailable before Stopped.
        assertEquals(ScreenResult.Unavailable(ScreenServiceState.NOT_ENABLED), h.operator.read("Scout", OWN))
        // 2. Stopped before Protected.
        h.link.connected(h.host)
        assertEquals(ScreenResult.Stopped, h.operator.read("Scout", OWN))
        h.control.resume()
        // 3. Protected before NotAuthorised.
        assertEquals(ScreenResult.Protected(OWN), h.operator.read("Scout", OWN))
        // 4. NotAuthorised before WrongWindow.
        h.show(TestWindow(MAIL))
        assertEquals(ScreenResult.NotAuthorised(NOTES), h.operator.read("Scout", NOTES))
        // 5. WrongWindow last.
        h.authorise()
        assertEquals(ScreenResult.WrongWindow(NOTES, MAIL), h.operator.read("Scout", NOTES))
    }

    @Test
    fun `only the app's own application window in front, without a lock screen, is operated`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val notes = TestWindow(NOTES, title = "Inbox", active = false) { button("Send") }

        h.show(TestWindow(NOTES, id = 9, type = AccessibilityWindowInfo.TYPE_SYSTEM), notes)
        assertEquals(ScreenResult.WrongWindow(NOTES, NOTES), h.operator.read("Scout", NOTES), "a system window of the same package")

        h.show(TestWindow("com.android.systemui", id = 9, type = AccessibilityWindowInfo.TYPE_SYSTEM), notes)
        assertEquals(ScreenResult.WrongWindow(NOTES, "com.android.systemui"), h.operator.read("Scout", NOTES))

        h.show(notes)
        assertEquals(ScreenResult.WrongWindow(NOTES, null), h.operator.read("Scout", NOTES), "nothing active")

        notes.active = true
        h.show(notes)
        h.facts.locked = true
        assertEquals(ScreenResult.WrongWindow(NOTES, null), h.operator.read("Scout", NOTES), "lock screen")
        h.facts.locked = false
        h.readOk()
    }

    @Test
    fun `an action re-checks the window just before pressing`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = inbox()
        h.show(window)
        val delete = h.readOk().elements.first { it.text == "Delete" }
        // Another app comes to the front while the operator is reading: simulated by the next window read.
        val mail = TestWindow(MAIL).build()
        val notesWindows = h.host.windows
        var reads = 0
        h.host.windows = notesWindows
        val switching = object : ScreenHost by h.host {
            override fun windows(): List<AccessibilityWindowInfo> = if (reads++ == 0) notesWindows else listOf(mail)
        }
        h.link.connected(switching)

        assertEquals(ScreenResult.WrongWindow(NOTES, MAIL), h.operator.tap("Scout", delete))
        assertTrue(window.log.isEmpty())
    }

    // ---- Acting ----

    @Test
    fun `tap presses the control with a node action, and says when it cannot`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, title = "Draft") {
            button("Save")
            button("Send") { enabled = false }
            button("Archive") { refuse = true }
        }
        h.show(window)
        val snapshot = h.readOk()

        assertEquals(ScreenResult.Ok(Unit), h.operator.tap("Scout", snapshot.named("Save")))
        assertIs<ScreenResult.NotSupported>(h.operator.tap("Scout", snapshot.named("Send")))
        assertIs<ScreenResult.Failed>(h.operator.tap("Scout", snapshot.named("Archive")))
        assertEquals(
            listOf(Performed("Save", AccessibilityNodeInfo.ACTION_CLICK), Performed("Archive", AccessibilityNodeInfo.ACTION_CLICK)),
            window.log,
        )
    }

    @Test
    fun `type sets the text, and presses the keyboard action only when asked`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, title = "Chat") {
            field("message") {
                hint = "Message"
                actions += AccessibilityAction.ACTION_IME_ENTER
            }
            field("subject") { hint = "Subject" }
            label("Today")
        }
        h.show(window)
        val snapshot = h.readOk()
        val message = snapshot.elements.single { it.hint == "Message" }

        assertEquals(ScreenResult.Ok(Unit), h.operator.type("Scout", message, "hello", submit = false))
        h.show(window)
        val again = h.readOk().elements.single { it.hint == "Message" }
        assertEquals(ScreenResult.Ok(Unit), h.operator.type("Scout", again, "hello", submit = true))
        assertEquals(
            listOf(
                Performed("message", AccessibilityNodeInfo.ACTION_SET_TEXT, "hello"),
                Performed("message", AccessibilityNodeInfo.ACTION_SET_TEXT, "hello"),
                Performed("message", AccessibilityAction.ACTION_IME_ENTER.id),
            ),
            window.log,
        )

        window.log.clear()
        val subject = snapshot.elements.single { it.hint == "Subject" }
        assertEquals(ScreenResult.NotSupported("That field has no keyboard action to press."), h.operator.type("Scout", subject, "hi", submit = true))
        assertEquals(ScreenResult.NotSupported("That element does not take text."), h.operator.type("Scout", snapshot.named("Today"), "hi", false))
        assertTrue(window.log.isEmpty(), "nothing typed when the whole action cannot be done")
    }

    @Test
    fun `Android 10 has no keyboard action to press`() = runTest {
        val h = OperatorHarness(this, sdkInt = 29)
        h.authorise()
        val window = TestWindow(NOTES) { field("message") { hint = "Message"; actions += AccessibilityAction.ACTION_IME_ENTER } }
        h.show(window)
        val message = h.readOk().elements.single()

        assertIs<ScreenResult.NotSupported>(h.operator.type("Scout", message, "hello", submit = true))
        assertTrue(window.log.isEmpty())
    }

    @Test
    fun `a field that became a password field is refused as sensitive`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES) { field("secret") { description = "Secret note" } })
        val field = h.readOk().named("Secret note")

        val masked = TestWindow(NOTES) { field("secret") { description = "Secret note"; password = true } }
        h.show(masked)
        assertEquals(ScreenResult.Sensitive, h.operator.type("Scout", field, "hello", submit = false))
        assertEquals(ScreenResult.Sensitive, h.operator.tap("Scout", field))
        assertTrue(masked.log.isEmpty())
    }

    @Test
    fun `scroll moves the list the element is in, in the direction asked`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = inbox()
        h.show(window)
        val row = h.readOk().elements[1]

        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN))
        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.UP, row))
        assertIs<ScreenResult.NotSupported>(h.operator.scroll("Scout", NOTES, ScrollDirection.LEFT))
        assertEquals(
            listOf(Performed("list", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), Performed("list", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)),
            window.log,
        )

        val directional = TestWindow(NOTES) { list { actions += AccessibilityAction.ACTION_SCROLL_DOWN } }
        h.show(directional)
        h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN).ok()
        assertEquals(listOf(Performed("list", AccessibilityAction.ACTION_SCROLL_DOWN.id)), directional.log)

        h.show(TestWindow(NOTES) { label("Nothing to scroll") })
        assertEquals(ScreenResult.NotFound("anything to scroll"), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN))
        assertEquals(ScreenResult.WrongWindow(NOTES, MAIL), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, row.copy(packageName = MAIL)))
    }

    @Test
    fun `back and home are global actions taken from an authorised app's window`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(inbox())

        h.operator.globalAction("Scout", NOTES, GlobalAction.BACK).ok()
        h.operator.globalAction("Scout", NOTES, GlobalAction.HOME).ok()
        h.host.globalResult = false
        assertIs<ScreenResult.Failed>(h.operator.globalAction("Scout", NOTES, GlobalAction.BACK))

        assertEquals(
            listOf(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, 1),
            h.host.globalActions,
        )
        assertEquals("com.android.launcher3", h.operator.launcherPackage())
    }

    // ---- Waiting ----

    @Test
    fun `wait_for returns the element once its text appears, numbered so it can be tapped`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES, title = "Draft") { label("Saving…") })

        val waiting = async { h.operator.waitFor("Scout", NOTES, "Saved", timeoutMs = 10_000) }
        runCurrent()
        advanceTimeBy(1_200)
        assertFalse(waiting.isCompleted)

        val saved = TestWindow(NOTES, title = "Draft") { button("Saved ✓", name = "saved") }
        h.show(saved)
        h.link.changed()
        runCurrent()

        val element = waiting.await().ok()
        assertEquals("Saved ✓", element.text)
        assertEquals(element, h.operator.find(NOTES, ScreenTarget.Numbered(element.snapshotId, element.number)).ok())
    }

    @Test
    fun `wait_for gives up after its timeout, and stops at once when the app leaves the front`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES) { label("Saving…") })

        val start = testScheduler.currentTime
        assertEquals(ScreenResult.NotFound("\"Saved\" did not appear within 3000 ms"), h.operator.waitFor("Scout", NOTES, "Saved", 3_000))
        assertEquals(3_000, testScheduler.currentTime - start)

        val waiting = async { h.operator.waitFor("Scout", NOTES, "Saved", timeoutMs = 60_000) }
        runCurrent()
        h.show(TestWindow(MAIL))
        h.link.changed()
        runCurrent()
        assertEquals(ScreenResult.WrongWindow(NOTES, MAIL), waiting.await())
    }

    // ---- Stop ----

    @Test
    fun `Stop ends a wait in progress at once and refuses every call until the owner resumes`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES) { label("Saving…") })

        val waiting = async { h.operator.waitFor("Scout", NOTES, "Saved", timeoutMs = 60_000) }
        runCurrent()
        val before = testScheduler.currentTime
        h.control.stop()
        runCurrent()

        assertTrue(waiting.isCompleted)
        assertEquals(ScreenResult.Stopped, waiting.await())
        assertEquals(before, testScheduler.currentTime, "no time passed")
        assertTrue(h.operator.stopped.value)
        assertEquals(ScreenResult.Stopped, h.operator.read("Scout", NOTES))
        assertEquals(ScreenResult.Stopped, h.operator.find(NOTES, ScreenTarget.ExactText("Saving…")))
        assertEquals(listOf(true), h.store.writes, "persisted")

        h.control.resume()
        runCurrent()
        h.readOk()
        assertEquals(listOf(true, false), h.store.writes)
    }

    @Test
    fun `a stored Stop holds after a restart, and an owner action before it is read wins`() = runTest {
        val stopped = OperatorHarness(this, store = InMemoryStopStore(value = true))
        stopped.authorise()
        stopped.show(TestWindow(NOTES))
        assertEquals(ScreenResult.Stopped, stopped.operator.read("Scout", NOTES))
        assertTrue(stopped.control.stopped.value)

        // A slow start-up read: until it finishes operating counts as stopped and calls wait for it.
        val held = CompletableDeferred<Unit>()
        val slow = InMemoryStopStore(value = false)
        val store = object : StopStore by slow {
            override suspend fun read(): Boolean {
                held.await()
                return slow.read()
            }
        }
        val restarting = OperatorHarness(this, store = InMemoryStopStore()).let { h ->
            val switch = StopSwitch(store, backgroundScope)
            h.authorise()
            h.show(TestWindow(NOTES))
            switch to AccessibilityScreenOperator(
                AuthorisedAppLookup.of(h.apps), { com.aicontrolcenter.core.operator.ProtectedPackages(OWN) }, StopSignal(switch), h.link, h.availability, h.facts, h.clock,
                backgroundScope, io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                main = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            )
        }
        val (switch, operator) = restarting
        val reading = async { operator.read("Scout", NOTES) }
        runCurrent()
        assertTrue(switch.stopped.value, "unknown until read: stopped")
        assertFalse(reading.isCompleted, "the call waits for the stored value")
        held.complete(Unit)
        runCurrent()
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(reading.await())

        // An owner action before the read finishes is newer than what was stored.
        val again = CompletableDeferred<Unit>()
        val storedStop = InMemoryStopStore(value = true)
        val switch2 = StopSwitch(
            object : StopStore by storedStop {
                override suspend fun read(): Boolean {
                    again.await()
                    return storedStop.read()
                }
            },
            backgroundScope,
        )
        runCurrent()
        switch2.resume()
        again.complete(Unit)
        runCurrent()
        assertFalse(switch2.stopped.value)
    }

    // ---- Sessions and the banner ----

    @Test
    fun `the banner shows from an agent's first call until it has been idle`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(inbox())
        val banner = h.host.indicator

        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.read("Scout", MAIL))
        h.operator.find(NOTES, ScreenTarget.ExactText("Invoice 42")).ok()
        runCurrent()
        assertTrue(banner.history.isEmpty(), "refused calls and assessments show nothing")

        h.readOk()
        runCurrent()
        assertEquals("Scout is operating Notes", banner.text)

        advanceTimeBy(com.aicontrolcenter.core.operator.ScreenOperator.SESSION_IDLE_MS - 1_000)
        h.readOk()
        advanceTimeBy(com.aicontrolcenter.core.operator.ScreenOperator.SESSION_IDLE_MS - 1_000)
        runCurrent()
        assertEquals("Scout is operating Notes", banner.text, "each call restarts the idle time")

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(banner.text)
    }

    @Test
    fun `sessions end with endSession, are shared by agents, and survive a reconnect`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(inbox())
        val banner = h.host.indicator

        h.readOk("Scout")
        h.readOk("Writer")
        runCurrent()
        assertEquals("Scout is operating Notes\nWriter is operating Notes", banner.text)

        h.operator.endSession("Scout")
        runCurrent()
        assertEquals("Writer is operating Notes", banner.text)

        val replacement = FakeScreenHost().apply { windows = h.host.windows }
        h.link.disconnected(h.host)
        h.link.connected(replacement)
        runCurrent()
        assertEquals("Writer is operating Notes", replacement.indicator.text)

        h.operator.endSession("Writer")
        h.operator.endSession("Nobody")
        runCurrent()
        assertNull(replacement.indicator.text)
    }

    @Test
    fun `the banner stays while a call is still operating, and its Stop button stops everything`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES) { label("Saving…") })
        val banner = h.host.indicator

        val waiting = async { h.operator.waitFor("Scout", NOTES, "Saved", timeoutMs = 60_000) }
        runCurrent()
        h.operator.endSession("Scout")
        runCurrent()
        assertEquals("Scout is operating Notes", banner.text, "not hidden mid-call")

        h.show(TestWindow(NOTES) { label("Saved") })
        h.link.changed()
        runCurrent()
        waiting.await().ok()
        runCurrent()
        assertNull(banner.text, "the ended session closes when its call returns")

        h.readOk()
        runCurrent()
        val onStop = banner.onStop!!
        onStop()
        runCurrent()
        assertTrue(h.control.stopped.value)
        assertNull(banner.text)
    }

    // ---- The window in front and the service state ----

    @Test
    fun `the active window's title is given only for an authorised app`() = runTest {
        val h = OperatorHarness(this)
        h.show(TestWindow(NOTES, title = "Invoice from Alice"))
        assertEquals(ActiveWindow(NOTES, null), h.operator.activeWindow())

        h.authorise()
        assertEquals(ActiveWindow(NOTES, "Invoice from Alice"), h.operator.activeWindow())

        h.facts.locked = true
        assertEquals(ActiveWindow(NOTES, null), h.operator.activeWindow())

        h.link.disconnected(h.host)
        assertNull(h.operator.activeWindow())
    }

    @Test
    fun `the service state follows Android`() = runTest {
        val h = OperatorHarness(this, connected = false)
        assertEquals(ScreenServiceState.NOT_ENABLED, h.operator.state.value)

        h.availability.restricted = true
        h.availability.onChange!!()
        // Android calls the observer on the main thread: the settings and PackageManager are read again off it (A7).
        assertEquals(ScreenServiceState.NOT_ENABLED, h.operator.state.value, "nothing is read on the observer's thread")
        runCurrent()
        assertEquals(ScreenServiceState.RESTRICTED, h.operator.state.value)

        h.availability.enabled = true
        h.availability.onChange!!()
        runCurrent()
        assertEquals(ScreenServiceState.STARTING, h.operator.state.value)
        assertEquals(ScreenResult.Unavailable(ScreenServiceState.STARTING), h.operator.read("Scout", NOTES))

        h.link.connected(h.host)
        runCurrent()
        assertEquals(ScreenServiceState.READY, h.operator.state.value)

        h.link.disconnected(h.host)
        runCurrent()
        assertEquals(ScreenServiceState.STARTING, h.operator.state.value)
    }

    @Test
    fun `Advanced Protection disables the service from Android 17 only`() {
        val on = FakeAvailability(enabled = true, advancedProtection = true)
        assertEquals(ScreenServiceState.ADVANCED_PROTECTION, screenState(connected = false, on, sdkInt = 37))
        assertEquals(ScreenServiceState.STARTING, screenState(connected = false, on, sdkInt = 36))
        assertEquals(ScreenServiceState.READY, screenState(connected = true, on, sdkInt = 37))
        assertEquals(ScreenServiceState.NOT_ENABLED, screenState(connected = false, FakeAvailability(), sdkInt = 37))
    }


}
