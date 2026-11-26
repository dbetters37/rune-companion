package com.aicontrolcenter.app.share

import com.aicontrolcenter.app.ui.workspace.WorkspaceViewModel
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.pickup.PickUpSafety

/**
 * How a share becomes memories or a workspace file (rule 34; WP16). Pure: no store is touched here.
 *
 * Every memory made from a share is an import ([ProvenanceOrigin.IMPORT], source [SOURCE]), never the owner's own
 * words ([ProvenanceOrigin.OWNER]), so recall frames it as data like anything else a memory holds; a NOTE in the space
 * the owner chose, tagged [TAG]. A text longer than one memory holds is split into parts of at most [PART_CHARS]
 * characters, cut after a line break or a space near the end, titled "<title> (part 2 of 3)" (as folder pick-up does).
 */
object ShareSaving {
    /** The most one memory holds (the memory service's limit, the same as folder pick-up's parts). */
    const val PART_CHARS: Int = 20_000

    /** The longest memory title (the memory service's limit). */
    const val MAX_TITLE_CHARS: Int = PickUpSafety.MAX_TITLE_CHARS

    /** The longest title the owner may type: a part's " (part 12 of 34)" still fits after it. */
    const val MAX_TYPED_TITLE_CHARS: Int = 160

    /** Where every memory made from a share says it came from. */
    const val SOURCE: String = "Shared from another app"

    /** The tag every memory made from a share carries, so the owner can find them. */
    const val TAG: String = "from-another-app"

    /** The title of shared text that came without one. */
    const val DEFAULT_TITLE: String = "Shared text"

    /** The workspace folder shares are saved into. */
    const val FOLDER: String = "From other apps"

    /** Where a memory made from a share came from: an import from another app, no agent, task or model. */
    fun provenance(at: Long): Provenance = Provenance(origin = ProvenanceOrigin.IMPORT, source = SOURCE, recordedAt = at)

    /**
     * Where the parts of [text] lie: at most [max] characters each, cut after the last line break or space in a part's
     * second half (else at [max]), never inside a surrogate pair; each part without its surrounding blanks, blank parts
     * left out. Ranges rather than strings, so the screen can count parts without copying a long text.
     */
    fun parts(text: String, max: Int = PART_CHARS): List<IntRange> {
        require(max >= 2) { "A part holds at least two characters." }
        val parts = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                var cut = -1
                var i = end - 1
                while (i > start + max / 2) {
                    if (text[i] == '\n' || text[i] == ' ') {
                        cut = i
                        break
                    }
                    i--
                }
                if (cut >= 0) end = cut + 1
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            }
            var first = start
            var last = end
            while (first < last && text[first].isWhitespace()) first++
            while (last > first && text[last - 1].isWhitespace()) last--
            if (first < last) parts += first until last
            start = end
        }
        return parts
    }

    /** "Notes (part 2 of 3)", the title cut so that the whole fits a memory's title. */
    fun partTitle(title: String, index: Int, count: Int): String {
        val base = title.trim().ifEmpty { DEFAULT_TITLE }
        if (count <= 1) return base.take(MAX_TITLE_CHARS).trim()
        val suffix = " (part ${index + 1} of $count)"
        return base.take(MAX_TITLE_CHARS - suffix.length).trim() + suffix
    }

    /** The memories [item] becomes in [space], titled [title] (with "(part n of m)" when it is split). */
    fun drafts(item: SharedItem, title: String, space: SpaceId, at: Long): List<MemoryDraft> {
        val parts = parts(item.text)
        return parts.mapIndexed { index, range ->
            MemoryDraft(
                scope = MemoryScope.Shared(space),
                kind = MemoryKind.NOTE,
                title = partTitle(title, index, parts.size),
                content = item.text.substring(range.first, range.last + 1),
                tags = setOf(TAG),
                provenance = provenance(at),
            )
        }
    }

    /**
     * The workspace file name for [item]: a file keeps its name (".txt" added when it has no ending); shared text is
     * named after [title] with ".txt", or ".md" for Markdown. Folders, hidden and control characters are dropped.
     */
    fun fileName(item: SharedItem, title: String): String {
        val raw = if (item.fromFile) item.name ?: title else title.trim().ifEmpty { item.name ?: DEFAULT_TITLE }
        val safe = WorkspaceViewModel.safeName(raw)
        val ending = if (item.markdown) ".md" else ".txt"
        if (!item.fromFile) return if (safe.lowercase().endsWith(ending)) safe else safe.take(120 - ending.length) + ending
        return if (safe.substringAfterLast('.', "").isEmpty() || safe.startsWith('.')) safe.take(120 - ending.length) + ending else safe
    }

    /** How many words [text] has: runs of characters between blanks. */
    fun words(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            val blank = c.isWhitespace()
            if (!blank && !inWord) count++
            inWord = !blank
        }
        return count
    }
}
