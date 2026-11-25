package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.activity.EgressOrigin
import com.aicontrolcenter.core.operator.LinkPolicy
import com.aicontrolcenter.core.operator.WebAddressPolicy
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/** `web.read_page`: one public page through [com.aicontrolcenter.core.operator.WebReader], as text. */
internal object WebTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(WebReadPage(d))

    /** True when the response is HTML by its type, or looks like it when no type was given. */
    fun isHtml(contentType: String?, body: String): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (type != null && type.isNotEmpty()) return type == "text/html" || type == "application/xhtml+xml"
        val start = body.trimStart().take(200).lowercase(Locale.ROOT)
        return start.startsWith("<!doctype html") || start.startsWith("<html")
    }

    /** True for content an agent can read as text. */
    fun isText(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return true
        return type.isEmpty() || type.startsWith("text/") || type.endsWith("+xml") || type.endsWith("+json") ||
            type in setOf("application/json", "application/xml", "application/javascript", "application/xhtml+xml", "application/rss+xml", "application/atom+xml")
    }
}

/** web.read_page */
internal class WebReadPage(private val d: OperatorToolDependencies) : OperatorTool(
    "web.read_page", "Read a web page",
    "Downloads one public web page (http or https) and returns its text; HTML is turned into readable text. The website learns " +
        "the address. No cookies or logins are used. Use \"include_links\" to also get the page's links.",
    objectSchema("url") {
        stringProp("url", "The page's address.")
        intProp("max_chars", "Return at most this many characters of text. Default 20000.", 500, 100_000)
        boolProp("include_links", "True to list the page's links after its text. Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val link = AppTools.link(d, args.text("url", LinkPolicy.MAX_LENGTH)!!, webOnly = true)
        // D8: an address on this phone or its network, written as such (a literal private address, localhost, a .local or
        // dotless name, a number in an unusual form), is refused before anyone is asked: the reader would refuse it anyway.
        // Names that resolve to such an address are still refused only when the page is read.
        WebAddressPolicy.literalRefusal(link.host.orEmpty())?.let { throw ToolCallRejected("The page was not read: $it") }
        val max = args.int("max_chars", d.limits.pageChars, 500..d.limits.maxPageChars)
        val links = args.bool("include_links", false)
        return assessment(
            "Read the web page ${q(link.link)}",
            prepared { read(link.link, max, links, context) },
            listOf("Address: ${q(link.link)}", "The website ${link.host} learns the address and this phone's network address."),
            dataGoesTo = link.host,
        )
    }

    private suspend fun read(url: String, max: Int, withLinks: Boolean, context: ToolContext): ToolOutcome {
        // The reader logs each request it sends off the phone; the origin lets each entry name the agent and the task.
        val origin = EgressOrigin(context.agent.id, context.taskId, context.rootTaskId)
        val page = when (val r = withContext(origin) { d.web.read(url, d.limits.web) }) {
            is WebReadResult.Page -> r
            is WebReadResult.Refused -> return ToolOutcome.failure("The page was not read: ${r.reason}")
            is WebReadResult.Failed -> return ToolOutcome.failure("The page could not be read: ${r.reason}")
        }
        val data = buildJsonObject {
            put("url", page.finalUrl)
            put("status", page.status)
            page.contentType?.let { put("contentType", it) }
            putJsonArray("hostsContacted") { page.hostsContacted.forEach { add(it) } }
        }
        if (!WebTools.isText(page.contentType)) {
            return ToolOutcome(false, "${page.finalUrl} is not a text page (${page.contentType}); it was not read.", data)
        }
        val html = WebTools.isHtml(page.contentType, page.body)
        val converted = if (html) HtmlText.convert(page.body, page.finalUrl, withLinks) else null
        val text = converted?.text ?: HtmlText.clean(page.body)
        val shown = text.take(max)
        val cut = shown.length < text.length || page.truncated
        val header = buildString {
            append("Page: ${page.finalUrl} (HTTP ${page.status}")
            page.contentType?.let { append(", $it") }
            append(")")
            converted?.title?.let { append("\nTitle: $it") }
        }
        val linkList = converted?.links?.takeIf { it.isNotEmpty() }?.let { links ->
            "\n\nLinks:\n" + links.mapIndexed { i, (label, href) -> "${i + 1}. $label — $href" }.joinToString("\n")
        }.orEmpty()
        val footer = if (cut) "\n\n[The page's text was cut at ${shown.length} characters.]" else ""
        val content = "$header\n\n$shown$footer$linkList"
        return if (page.status >= 400) ToolOutcome(false, "The site answered HTTP ${page.status}.\n$content", data) else ToolOutcome(true, content, data)
    }
}
