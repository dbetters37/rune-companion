package com.aicontrolcenter.device.catalog

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnownAiAppsTest {

    @Test
    fun `no package appears twice`() {
        val duplicates = KnownAiApps.all.groupBy { it.packageName }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet(), duplicates)
        assertEquals(KnownAiApps.all.size, KnownAiApps.packageNames.size)
    }

    @Test
    fun `every entry is a plausible android package with a name and a vendor`() {
        val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        for (app in KnownAiApps.all) {
            assertTrue(packagePattern.matches(app.packageName), "bad package id: ${app.packageName}")
            assertTrue(app.name.isNotBlank(), "${app.packageName} has no name")
            assertTrue(app.vendor.isNotBlank(), "${app.packageName} has no vendor")
        }
    }

    @Test
    fun `every prompt link is a fillable https template containing the placeholder`() {
        val links = KnownAiApps.all.flatMap { app -> app.promptLinks.map { app.packageName to it } }
        assertTrue(links.isNotEmpty(), "the catalog should carry at least the documented Claude link")
        for ((packageName, link) in links) {
            assertTrue(PromptLinkTemplate.PLACEHOLDER in link.template, "$packageName: ${link.template} lacks {prompt}")
            assertTrue(PromptLinkTemplate.isUsable(link.template), "$packageName: ${link.template} is not usable")
            assertTrue(link.opens.isNotBlank(), "$packageName: link has no description")
        }
    }

    @Test
    fun `only vendor-documented links count as verified`() {
        val verified = KnownAiApps.all.filter { it.verifiedPromptLink != null }.map { it.packageName }
        assertEquals(listOf("com.anthropic.claude"), verified)
        assertEquals("https://claude.ai/code/new?q={prompt}", KnownAiApps.find("com.anthropic.claude")?.verifiedPromptLink?.template)

        val chatGpt = assertNotNull(KnownAiApps.find("com.openai.chatgpt"))
        assertTrue(chatGpt.promptLinks.isNotEmpty(), "the community-observed ChatGPT link is kept as a lead")
        assertNull(chatGpt.verifiedPromptLink, "a community-observed link must not be treated as verified")
    }

    @Test
    fun `termux and local runners host local models, cloud apps and clients do not`() {
        fun hosts(packageName: String) = assertNotNull(KnownAiApps.find(packageName), packageName).hostsLocalModels
        assertTrue(hosts("com.termux"))
        assertTrue(hosts("com.pocketpalai"))
        assertTrue(hosts("com.google.ai.edge.gallery"))
        assertTrue(hosts("ai.mlc.mlcchat"))
        assertFalse(hosts("com.openai.chatgpt"))
        assertFalse(hosts("com.anthropic.claude"))
        assertFalse(hosts("com.brave.browser"))
        // A client for somebody else's Ollama server runs no model itself.
        assertFalse(hosts("com.freakurl.apps.ollama"))

        for (app in KnownAiApps.all) {
            val expected = app.kind == KnownAiAppKind.LOCAL_RUNNER || app.kind == KnownAiAppKind.TERMINAL
            assertEquals(expected, app.hostsLocalModels, app.packageName)
        }
    }

    @Test
    fun `id verification follows the evidence`() {
        assertTrue(assertNotNull(KnownAiApps.find("com.anthropic.claude")).idVerified)
        assertTrue(assertNotNull(KnownAiApps.find("com.termux")).idVerified)
        assertFalse(assertNotNull(KnownAiApps.find("com.openai.chatgpt")).idVerified, "known from a store listing only")
        val tongyi = assertNotNull(KnownAiApps.find("com.aliyun.tongyi"))
        assertEquals(IdEvidence.UNCONFIRMED, tongyi.idEvidence)
        assertFalse(tongyi.idVerified)
    }

    @Test
    fun `lookup is exact and case-sensitive`() {
        assertEquals("ChatterUI", KnownAiApps.find("com.Vali98.ChatterUI")?.name)
        assertNull(KnownAiApps.find("com.vali98.chatterui"))
        assertNull(KnownAiApps.find("com.deepseek.chat.nov"), "look-alike ids are not the catalogued app")
        assertNull(KnownAiApps.find(""))
    }

    @Test
    fun `companion packages are not AI apps`() {
        assertEquals(emptySet(), CompanionPackages.notInCatalog intersect KnownAiApps.packageNames)
        assertTrue(CompanionPackages.TERMUX in KnownAiApps.packageNames)
    }
}
