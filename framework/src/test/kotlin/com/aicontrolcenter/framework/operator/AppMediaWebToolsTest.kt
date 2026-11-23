package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.EmailDraft
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaItem
import com.aicontrolcenter.core.operator.MediaKind
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.AppCall
import com.aicontrolcenter.testkit.contract.PreparedActionChecks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppMediaWebToolsTest {
    private val f = OperatorFixture()

    @Test
    fun `media is listed as metadata and copied without replacing a file`() = runTest {
        f.media.items += MediaItem(MediaId("img:1"), MediaKind.IMAGE, "beach.jpg", "image/jpeg", 2_048, 400, 300, takenAt = 1_700_000_000_000, album = "Camera")
        f.media.items += MediaItem(MediaId("vid:2"), MediaKind.VIDEO, "clip.mp4", takenAt = 1_600_000_000_000)
        f.media.contents[MediaId("img:1")] = byteArrayOf(1, 2, 3)
        val photos = f.ok("media.list", "kinds" to listOf("image"))
        assertTrue("img:1 · \"beach.jpg\" · image" in photos.content && "clip.mp4" !in photos.content, photos.content)
        val recent = f.ok("media.list", "after" to "2023-01-01")
        assertTrue("beach.jpg" in recent.content && "clip.mp4" !in recent.content, recent.content)
        assertTrue("date like" in f.rejected("media.list", "after" to "yesterday"))

        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("media.copy_to_workspace"), json("id" to "img:1", "to" to "photos/beach.jpg"), f.context,
            change = { f.put("photos/beach.jpg", "mine") }, nothingDone = { f.file("photos/beach.jpg").readText() == "mine" && f.media.copies.isEmpty() },
        )
        f.file("photos/beach.jpg").delete()
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("media.copy_to_workspace"), json("id" to "img:1", "to" to "photos/beach.jpg"), f.context)
        assertEquals(3L, f.file("photos/beach.jpg").length())
    }

    @Test
    fun `apps are listed and opened by package, and protected apps are refused before anything runs`() = runTest {
        f.apps.installed += InstalledAppInfo("com.example.notes", "Notes")
        assertTrue("Notes (com.example.notes)" in f.ok("apps.list", "query" to "note").content)
        val (open, _) = f.run("apps.open", "package" to "com.example.notes")
        assertEquals("Open the app \"Notes\" (com.example.notes)", open.summary)
        assertEquals(AppCall.Open("com.example.notes"), f.apps.calls.last())
        assertTrue("protected" in f.rejected("apps.open", "package" to "com.android.settings"))
        assertTrue("protected" in f.rejected("apps.open", "package" to OperatorFixture.OWN))
        f.extraProtected = setOf("com.oem.securitycenter")
        assertTrue("protected" in f.rejected("apps.uninstall", "package" to "com.oem.securitycenter"))
        assertTrue("package name" in f.rejected("apps.open", "package" to "not a package"))
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("apps.open"), json("package" to "com.example.notes"), f.context,
            change = { f.apps.installed.clear() }, nothingDone = { f.apps.calls.size == 1 },
        )
    }

    @Test
    fun `links go through the link policy and name where the address goes`() = runTest {
        val (link, outcome) = f.run("apps.open_link", "link" to "  HTTPS://example.com/a?b=1 ")
        assertEquals("example.com", link.dataGoesTo)
        assertEquals("Open the link \"https://example.com/a?b=1\"", link.summary)
        assertTrue(outcome.ok)
        assertEquals(AppCall.OpenLink("https://example.com/a?b=1"), f.apps.calls.last())
        assertEquals("the app that opens geo: links", f.assess("apps.open_link", "link" to "geo:48.85,2.35").dataGoesTo)
        for (bad in listOf("intent://x#Intent;end", "javascript:alert(1)", "file:///sdcard/x", "mailto:a@b.c", "https://a.com/‮b", "${OperatorFixture.OWN}://approve")) {
            f.rejected("apps.open_link", "link" to bad)
        }
        val (browser, _) = f.run("browser.open", "url" to "https://news.example.org/today")
        assertEquals("news.example.org", browser.dataGoesTo)
        assertEquals(AppCall.OpenInBrowser("https://news.example.org/today"), f.apps.calls.last())
        assertTrue("http" in f.rejected("browser.open", "url" to "geo:1,2"))
        assertTrue(f.tool("browser.open").spec.sendsDataOffDevice && f.tool("apps.open_link").spec.sendsDataOffDevice)
    }

    @Test
    fun `compose tools show every field exactly and send nothing themselves`() = runTest {
        val (email, outcome) = f.run(
            "apps.compose_email",
            "to" to listOf("ann@example.com"), "cc" to listOf("bob@example.com"), "subject" to "Re: plan", "body" to "Hi Ann,\n\nsee you​ soon",
        )
        assertTrue(outcome.ok, outcome.content)
        assertEquals("your email app", email.dataGoesTo)
        assertTrue("Body: \"Hi Ann,⏎\n⏎\nsee you⟨U+200B⟩ soon\"" in email.preview, email.preview)
        assertTrue("Cc: \"bob@example.com\"" in email.preview)
        assertEquals(AppCall.ComposeEmail(EmailDraft(listOf("ann@example.com"), listOf("bob@example.com"), emptyList(), "Re: plan", "Hi Ann,\n\nsee you​ soon")), f.apps.calls.last())
        assertTrue("email address" in f.rejected("apps.compose_email", "to" to listOf("not an address")))

        val (sms, _) = f.run("apps.compose_sms", "number" to "+44 20 7946 0958", "body" to "On my way")
        assertTrue("you to send" in sms.summary && "\"On my way\"" in sms.preview, sms.preview)
        assertEquals(AppCall.ComposeSms("+44 20 7946 0958", "On my way"), f.apps.calls.last())
        assertTrue("phone number" in f.rejected("apps.dial", "number" to "call me"))
        f.ok("apps.dial", "number" to "112")
        assertEquals(AppCall.Dial("112"), f.apps.calls.last())

        val (share, _) = f.run("apps.share_text", "text" to "the list")
        assertEquals("the app you choose", share.dataGoesTo)
        assertTrue("No app" in f.fails("apps.share_text", "text" to "x", "app" to "com.example.absent").content)
        for (a in listOf(email, sms, share)) assertPrepared(a)
    }

    @Test
    fun `a start Android blocks in the background is reported, not claimed`() = runTest {
        f.apps.inBackground = true
        val outcome = f.fails("apps.dial", "number" to "112")
        assertTrue("Not opened now" in outcome.content && "Tap to open" in outcome.content, outcome.content)
    }

    @Test
    fun `install is consequential, names the exact file and refuses a changed APK`() = runTest {
        f.put("downloads/app.apk", "apk-bytes-v1")
        val assessment = f.assess("apps.install", "path" to "downloads/app.apk")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue(assessment.details.any { it.startsWith("SHA-256: ") }, assessment.preview)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("apps.install"), json("path" to "downloads/app.apk"), f.context,
            change = { f.put("downloads/app.apk", "apk-bytes-v2") }, nothingDone = { f.apps.calls.isEmpty() },
        ).also { assertTrue("content of" in it.content, it.content) }
        val installed = PreparedActionChecks.assertRunsWhenUnchanged(f.tool("apps.install"), json("path" to "downloads/app.apk"), f.context)
        assertTrue("installed" in installed.content, installed.content)
        assertTrue(".apk" in f.rejected("apps.install", "path" to "notes.txt"))

        f.apps.installOutcome = AppActionResult.UserCancelled
        assertTrue("cancelled" in f.fails("apps.install", "path" to "downloads/app.apk").content)
    }

    @Test
    fun `uninstall is consequential and goes through Android's confirmation`() = runTest {
        f.apps.installed += InstalledAppInfo("com.example.game", "Game")
        val (assessment, outcome) = f.run("apps.uninstall", "package" to "com.example.game")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertEquals("Remove the app \"Game\" (com.example.game) from this phone", assessment.summary)
        assertTrue(outcome.ok && "removed" in outcome.content, outcome.content)
        assertEquals(AppCall.Uninstall("com.example.game"), f.apps.calls.last())
    }

    @Test
    fun `a web page is read as text, with the hosts contacted for the egress log`() = runTest {
        f.web.page(
            "https://example.com/news",
            "<html><head><title>News &amp; more</title><style>p{}</style></head><body><h1>Today</h1><p>First&nbsp;item</p>" +
                "<a href=\"/more\">More</a><script>track()</script></body></html>",
        )
        val (assessment, outcome) = f.run("web.read_page", "url" to "https://example.com/news", "include_links" to true)
        assertEquals("example.com", assessment.dataGoesTo)
        assertRisk(RiskLevel.SAFE, assessment)
        assertTrue(outcome.ok, outcome.content)
        assertTrue("Title: News & more" in outcome.content && "# Today" in outcome.content && "First item" in outcome.content, outcome.content)
        assertTrue("track()" !in outcome.content && "p{}" !in outcome.content)
        assertTrue("1. More — https://example.com/more" in outcome.content, outcome.content)
        assertEquals(listOf("example.com"), outcome.data!!.jsonObject.getValue("hostsContacted").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `web pages are cut, refused addresses and error statuses are failures`() = runTest {
        f.web.page("https://example.com/long", "x".repeat(5_000), contentType = "text/plain")
        val cut = f.ok("web.read_page", "url" to "https://example.com/long", "max_chars" to 1_000)
        assertTrue("cut at 1000 characters" in cut.content)
        assertTrue("only public" in f.fails("web.read_page", "url" to "http://127.0.0.1/admin").content)
        f.web.page("https://example.com/missing", "<p>Not here</p>", status = 404)
        assertTrue("HTTP 404" in f.fails("web.read_page", "url" to "https://example.com/missing").content)
        f.web.pages["https://example.com/pic"] = WebReadResult.Page("https://example.com/pic", 200, "image/png", "\u0089PNG", false, listOf("example.com"))
        assertTrue("not a text page" in f.fails("web.read_page", "url" to "https://example.com/pic").content)
        assertTrue("http" in f.rejected("web.read_page", "url" to "ftp://example.com/x"))
        assertFalse(f.web.requests.any { it.first.startsWith("ftp") })
    }
}
