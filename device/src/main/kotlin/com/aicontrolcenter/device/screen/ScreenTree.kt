package com.aicontrolcenter.device.screen

import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.aicontrolcenter.core.operator.ScreenBounds
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.WindowDigest

/**
 * One window's elements, read once: what [ScreenReader] found, with the node behind each so an
 * action reaches exactly the control that was matched. Elements carry snapshot id 0 and number 0
 * until [SnapshotStore] numbers them.
 */
internal class ScreenTree(
    val packageName: String,
    val windowTitle: String?,
    /** Elements and withheld fields, in reading order (depth first, as the screen reads). */
    val entries: List<ScreenEntry>,
    /** Password and sensitive fields found; neither they nor anything inside them is read. */
    val withheld: Int,
    /** The walk stopped at its node or depth limit. */
    val truncated: Boolean,
    /**
     * The fingerprint of the whole window ([WindowDigest]): every visible element in reading
     * order with its texts uncut, also those past the node limit when the read went on for them
     * ([ScreenReader.read] with `wholeWindow`). Null when the walk did not reach the end of the
     * window: the node limit without `wholeWindow`, the depth limit, or more than the reader's
     * ceiling of elements.
     */
    val digest: String? = null,
    /** How many elements the window holds that the model may see, counting those only walked for [digest]; at least [visible]'s size. */
    val elementCount: Int = entries.count { !it.withheld },
) {
    /** The entries the model may see: everything but withheld fields. */
    val visible: List<ScreenEntry> get() = entries.filter { !it.withheld }

    /** Entries that are [ScreenElement.sameControlAs] [element]. */
    fun matching(element: ScreenElement): List<ScreenEntry> = entries.filter { it.element.sameControlAs(element) }
}

/** An element of a [ScreenTree] and the node it was read from. */
internal class ScreenEntry(
    val element: ScreenElement,
    val node: AccessibilityNodeInfo,
    /** A password field or a view marked sensitive: never shown, never acted on. */
    val withheld: Boolean,
    /** For an element that is neither clickable nor editable: the clickable control around it, which a tap would really press. */
    val clickTarget: ScreenEntry?,
    /** The nearest scrollable element around this one. */
    private val scrollAncestor: ScreenEntry?,
    /** Where the reader found it in the tree, so [ScreenReader.reread] can ask the app for the same place again. */
    internal val mirror: ScreenReader.Mirror,
) {
    /** What a tap on this element presses: itself, or the control around it. */
    val pressed: ScreenEntry get() = clickTarget ?: this

    /** What a scroll of this element moves: itself if it scrolls, else the nearest scrolling element around it. */
    val scrollTarget: ScreenEntry? get() = if (element.scrollable) this else scrollAncestor
}

/**
 * Reads an accessibility node tree into a [ScreenTree].
 *
 * - Invisible nodes and everything under them are skipped.
 * - Password fields (also by input type) and, on Android 14+, views marked
 *   `accessibilityDataSensitive` are counted as withheld; their text, and every node under them,
 *   is never read. Their texts are also left out of every label and context.
 * - An element is any visible node that can be acted on (click, long click, edit, scroll, check)
 *   or that shows text or a description.
 * - [ScreenElement.label] merges the texts of the node's descendants that are not controls of
 *   their own, its `labeledBy` text, state description, tooltip and action labels.
 * - [ScreenElement.context] is the list row the node is in ("Row: …") or else its nearest
 *   ancestor with a text of its own ("In: …"), then the window title.
 * - [ScreenElement.nodeKey] is `getUniqueId()` when the app sets one (Android 13+), else the
 *   child-index path from the window root.
 *
 * Every getter is read once per node; walking happens off the main thread because `getChild`
 * may be a call into the other app.
 *
 * While it walks, the reader fingerprints every element with its texts uncut ([ScreenTree.digest]).
 * With `wholeWindow` it goes on past [maxNodes], reading nothing more into the tree, only to
 * finish that fingerprint, for at most [digestMaxElements] elements in all.
 */
internal class ScreenReader(
    private val maxNodes: Int = MAX_NODES,
    private val maxDepth: Int = MAX_DEPTH,
    private val digestMaxElements: Int = ScreenOperator.DIGEST_MAX_ELEMENTS,
) {
    /**
     * [wholeWindow]: past the node limit, keep walking only to fingerprint the rest of the window
     * (for an approval preview and its re-check, which must pin what they cannot list).
     */
    fun read(
        root: AccessibilityNodeInfo,
        packageName: String,
        windowTitle: String?,
        wholeWindow: Boolean = false,
        checkCancelled: () -> Unit = {},
    ): ScreenTree {
        val walk = Walk(checkCancelled, wholeWindow)
        val top = walk.mirror(root, null, "", 0)
        val entries = ArrayList<ScreenEntry>()
        val byMirror = HashMap<Mirror, ScreenEntry>()
        val title = windowTitle?.let(::clean)
        fun build(m: Mirror) {
            if (m.isElement) {
                val clickTarget = if (m.clickable || m.editable) null else m.ancestors().firstOrNull { it.clickable }?.let(byMirror::get)
                val scrollAncestor = m.ancestors().firstOrNull { it.scrollable }?.let(byMirror::get)
                val entry = ScreenEntry(element(m, packageName, title), m.node, m.withheld, clickTarget, scrollAncestor, m)
                byMirror[m] = entry
                entries += entry
            }
            for (child in m.children) build(child)
        }
        top?.let(::build)
        // Past the node limit nothing but layout was found: the list holds every element after all.
        val truncated = walk.truncated && !walk.listedAll
        return ScreenTree(packageName, title, entries, walk.withheld, truncated, walk.digest(), walk.elements - walk.withheld)
    }

    /**
     * [entry]'s control as it is now, asked from the app node by node: its own node, everything
     * under it that its label is made of, and the row or ancestors its context came from. Every
     * node is refreshed before it is read, because a tree walked through `getChild` is served
     * from Android's node cache, which can lag behind the app by a second. Returns null when the
     * control, or anything on the way to it, is gone or no longer where it was, or when the row
     * around it is no longer shaped as it was read.
     *
     * A list that re-bound the row to another item keeps the button's own text ("Delete") and
     * changes the row's: the element this returns then has another [ScreenElement.context] or
     * [ScreenElement.label], which [ScreenElement.sameControlAs] tells apart.
     */
    fun reread(entry: ScreenEntry, packageName: String, windowTitle: String?, checkCancelled: () -> Unit = {}): ScreenElement? {
        val old = entry.mirror
        // The ancestors the context was read from, nearest first, up to the one that decided it.
        val chain = contextChain(old)
        val fresh = Fresh(old.path, checkCancelled)
        val top = chain.lastOrNull() ?: old
        val topMirror = fresh.mirror(top.node, null, top.path) ?: return null
        fresh.descend(topMirror, 0, row = chain.isNotEmpty() && topMirror.collectionItem)
        val target = fresh.target ?: return null
        if (fresh.failed) return null
        // The same shape as when it was read: what was a row is a row, what was a list is a list.
        val now = generateSequence(target.parent) { it.parent }.toList()
        if (now.size != chain.size) return null
        for ((was, isNow) in chain.zip(now)) {
            if (was.collectionItem != isNow.collectionItem || (was.collection || was.scrollable) != (isNow.collection || isNow.scrollable)) return null
        }
        return element(target, packageName, windowTitle?.let(::clean))
    }

    /** The ancestors [contextOf] looks at for [m], nearest first: up to the first list, row or ancestor with words, at most [MAX_CONTEXT_LEVELS]. */
    private fun contextChain(m: Mirror): List<Mirror> {
        val chain = ArrayList<Mirror>(MAX_CONTEXT_LEVELS)
        var ancestor = m.parent
        while (ancestor != null && chain.size < MAX_CONTEXT_LEVELS) {
            chain += ancestor
            if (ancestor.collection || ancestor.scrollable || ancestor.collectionItem || ancestor.words != null) break
            ancestor = ancestor.parent
        }
        return chain
    }

    private fun element(m: Mirror, packageName: String, windowTitle: String?): ScreenElement = ScreenElement(
        snapshotId = 0,
        number = 0,
        packageName = packageName,
        role = m.role,
        text = m.text,
        description = m.description,
        hint = m.hint,
        resourceId = m.resourceId,
        label = if (m.withheld) null else labelOf(m),
        context = contextOf(m, windowTitle),
        nodeKey = m.uniqueId?.let { "id:$it" } ?: "path:${m.path.ifEmpty { "/" }}",
        bounds = ScreenBounds(m.bounds.left, m.bounds.top, m.bounds.right, m.bounds.bottom),
        clickable = m.clickable,
        editable = m.editable,
        scrollable = m.scrollable,
        checkable = m.checkable,
        checked = m.checked,
        enabled = m.enabled,
        focused = m.focused,
        selected = m.selected,
        cutChars = m.cutChars,
    )

    private fun labelOf(m: Mirror): String? {
        val parts = LinkedHashSet<String>()
        fun collect(node: Mirror) {
            for (child in node.children) {
                // Withheld fields are never read; other controls are elements of their own.
                if (child.withheld || child.isOwnControl) continue
                child.words?.let(parts::add)
                collect(child)
            }
        }
        collect(m)
        m.labeledBy?.let(parts::add)
        m.stateDescription?.let(parts::add)
        m.tooltip?.let(parts::add)
        parts += m.actionLabels
        m.text?.let(parts::remove)
        m.description?.let(parts::remove)
        return parts.joinToString(LABEL_SEPARATOR).take(MAX_LABEL).ifBlank { null }
    }

    /**
     * The nearest list row around [m] (named by its own text, else the first texts in it other
     * than [m]'s), else the nearest ancestor with a text or description of its own; then the
     * window title. Texts of unrelated siblings are not used, so a banner appearing elsewhere
     * does not change it.
     */
    private fun contextOf(m: Mirror, windowTitle: String?): String? {
        var found: String? = null
        var ancestor = m.parent
        var levels = 0
        while (ancestor != null && levels < MAX_CONTEXT_LEVELS) {
            // A list or scrolling area holds many rows; its texts do not say which row this is.
            if (ancestor.collection || ancestor.scrollable) break
            if (ancestor.collectionItem) {
                found = (ancestor.words ?: textsWithin(ancestor, exclude = m))?.let { "Row: $it" }
                break
            }
            val words = ancestor.words
            if (words != null) {
                found = "In: ${words.take(MAX_CONTEXT_TEXT)}"
                break
            }
            ancestor = ancestor.parent
            levels++
        }
        return listOfNotNull(found, windowTitle).joinToString(" · ").ifEmpty { null }
    }

    /** The first texts under [ancestor], outside [exclude] and withheld fields, joined. */
    private fun textsWithin(ancestor: Mirror, exclude: Mirror): String? {
        val found = ArrayList<String>(CONTEXT_TEXTS)
        fun visit(node: Mirror, depth: Int) {
            for (child in node.children) {
                if (found.size >= CONTEXT_TEXTS) return
                if (child === exclude || child.withheld) continue
                child.words?.let(found::add)
                if (depth < CONTEXT_DEPTH) visit(child, depth + 1)
            }
        }
        visit(ancestor, 1)
        return found.joinToString(" · ").take(MAX_CONTEXT_TEXT).ifBlank { null }
    }

    /**
     * One node's facts, copied once. [light]: made only to fingerprint a node past the node limit,
     * so whatever would be another call into the app is skipped.
     */
    internal class Mirror(val node: AccessibilityNodeInfo, val parent: Mirror?, val path: String, val withheld: Boolean, light: Boolean = false) {
        val children = ArrayList<Mirror>(0)
        val role: String = node.className?.toString()?.substringAfterLast('.')?.ifBlank { null } ?: "View"

        /** The texts as the app gives them, trimmed and uncut: what the fingerprint pins. */
        private val fullText: String? = if (withheld) null else whole(node.text)
        private val fullDescription: String? = whole(node.contentDescription)
        private val fullHint: String? = if (withheld) null else whole(node.hintText)
        val text: String? = fullText?.take(MAX_TEXT)
        val description: String? = fullDescription?.take(MAX_TEXT)
        val hint: String? = fullHint?.take(MAX_TEXT)

        /** How many characters [text], [description] and [hint] leave out together. */
        val cutChars: Int = listOf(fullText, fullDescription, fullHint).sumOf { ((it?.length ?: 0) - MAX_TEXT).coerceAtLeast(0) }
        val resourceId: String? = node.viewIdResourceName?.ifBlank { null }
        private val actions: List<AccessibilityAction> = node.actionList.orEmpty()
        val clickable: Boolean = node.isClickable || actions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        val longClickable: Boolean = node.isLongClickable
        val editable: Boolean = node.isEditable
        val scrollable: Boolean = node.isScrollable
        val checkable: Boolean = node.isCheckable
        // Android 16 adds a three-state getChecked(); "partly checked" reads as not checked here.
        @Suppress("DEPRECATION")
        val checked: Boolean = node.isChecked
        val enabled: Boolean = node.isEnabled
        val focused: Boolean = node.isFocused
        val selected: Boolean = node.isSelected
        val collection: Boolean = node.collectionInfo != null
        val collectionItem: Boolean = node.collectionItemInfo != null
        val stateDescription: String? =
            if (!withheld && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) clean(node.stateDescription) else null
        val tooltip: String? = if (withheld) null else clean(node.tooltipText)
        val actionLabels: List<String> = if (withheld) emptyList() else actions.mapNotNull { clean(it.label) }
        val uniqueId: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) node.uniqueId?.ifBlank { null } else null
        val bounds: Rect = Rect().also(node::getBoundsInScreen)

        /** The text a label of a field or checkbox comes from; read only for such controls (it is another call into the app). */
        val labeledBy: String? =
            if (light || withheld || !(editable || checkable)) {
                null
            } else {
                @Suppress("DEPRECATION")
                node.labeledBy?.takeUnless { isWithheld(it) }?.let { clean(it.text) ?: clean(it.contentDescription) }
            }

        /** A control of its own: never merged into an ancestor's label. */
        val isOwnControl: Boolean get() = clickable || editable || scrollable || checkable

        val isElement: Boolean
            get() = withheld || clickable || longClickable || editable || scrollable || checkable || text != null || description != null

        val words: String? get() = text ?: description

        fun ancestors(): Sequence<Mirror> = generateSequence(parent) { it.parent }

        /** This element's part of the window's fingerprint: its uncut texts and its state; of a withheld field only that it is there. */
        fun feed(digest: WindowDigest) {
            if (withheld) {
                digest.withheld()
            } else {
                digest.element(role, fullText, fullDescription, fullHint, resourceId, stateDescription, checkable, checked, selected, enabled)
            }
        }
    }

    private inner class Walk(private val checkCancelled: () -> Unit, private val wholeWindow: Boolean) {
        var visited = 0
        var withheld = 0
        var truncated = false

        /** Elements fingerprinted so far, withheld fields included. */
        var elements = 0
        private val fingerprint = WindowDigest()

        /** Still true while every visible element met so far is in the fingerprint. */
        private var whole = true
        private var passed = 0
        private var passedElements = 0

        /** The walk went past the node limit to the end of the window and met no element there. */
        val listedAll: Boolean get() = whole && passedElements == 0

        /** The window's fingerprint, or null when the walk did not cover all of it. */
        fun digest(): String? = if (whole) fingerprint.finish() else null

        fun mirror(node: AccessibilityNodeInfo, parent: Mirror?, path: String, depth: Int): Mirror? {
            if (visited >= maxNodes) {
                truncated = true
                if (wholeWindow) pass(node, depth) else whole = false
                return null
            }
            if (!node.isVisibleToUser) return null
            visited++
            if (visited % CANCEL_CHECK_EVERY == 0) checkCancelled()
            val hidden = isWithheld(node)
            val mirror = Mirror(node, parent, path, hidden)
            count(mirror)
            if (hidden) {
                withheld++
                return mirror
            }
            val count = node.childCount
            if (count > 0 && depth >= maxDepth) {
                truncated = true
                whole = false
                return mirror
            }
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                mirror(child, mirror, "$path/$i", depth + 1)?.let(mirror.children::add)
            }
            return mirror
        }

        /** Past the node limit: [node] and what is under it go into the fingerprint and nowhere else. */
        private fun pass(node: AccessibilityNodeInfo, depth: Int) {
            if (!whole || !node.isVisibleToUser) return
            passed++
            if (passed > digestMaxElements * PASS_NODES_PER_ELEMENT) {
                whole = false
                return
            }
            if (passed % CANCEL_CHECK_EVERY == 0) checkCancelled()
            val hidden = isWithheld(node)
            val light = Mirror(node, null, "", hidden, light = true)
            if (light.isElement) passedElements++
            count(light)
            if (hidden) {
                withheld++
                return
            }
            val count = node.childCount
            if (count > 0 && depth >= maxDepth) {
                whole = false
                return
            }
            for (i in 0 until count) {
                if (!whole) return
                pass(node.getChild(i) ?: continue, depth + 1)
            }
        }

        private fun count(m: Mirror) {
            if (!whole || !m.isElement) return
            if (elements >= digestMaxElements) {
                whole = false
                return
            }
            elements++
            m.feed(fingerprint)
        }
    }

    /**
     * A second, small walk for [reread]: every node is refreshed before it is read. It follows
     * the child indexes of [targetPath] down from the top it is given; under a list row it also
     * reads what [textsWithin] reads, and under the target what [labelOf] reads.
     */
    private inner class Fresh(private val targetPath: String, private val checkCancelled: () -> Unit) {
        var target: Mirror? = null

        /** More nodes than a whole read takes: the screen is not what it was. */
        var failed = false
        private var visited = 0

        /** [node] asked again from the app, hung under [parent]; null when it is gone or hidden now. */
        fun mirror(node: AccessibilityNodeInfo, parent: Mirror?, path: String): Mirror? {
            if (failed) return null
            if (++visited > maxNodes) {
                failed = true
                return null
            }
            if (visited % CANCEL_CHECK_EVERY == 0) checkCancelled()
            if (!node.refresh() || !node.isVisibleToUser) return null
            return Mirror(node, parent, path, isWithheld(node)).also { parent?.children?.add(it) }
        }

        /** [m] is on the way to the target, [level] levels under the top; [row]: the top is a list row, whose texts name it. */
        fun descend(m: Mirror, level: Int, row: Boolean) {
            if (m.path == targetPath) {
                target = m
                // A label keeps MAX_LABEL characters, after the control's own text and description (each with its separator) are taken out of it.
                labelRoom = MAX_LABEL + (m.text?.length ?: 0) + (m.description?.length ?: 0) + 4 * LABEL_SEPARATOR.length
                labelParts(m)
                return
            }
            if (m.withheld) return
            for (i in 0 until m.node.childCount) {
                val path = "${m.path}/$i"
                val onTheWay = targetPath == path || targetPath.startsWith("$path/")
                if (!onTheWay && !(row && level < CONTEXT_DEPTH)) continue
                val child = mirror(m.node.getChild(i) ?: continue, m, path) ?: continue
                if (onTheWay) descend(child, level + 1, row) else rowTexts(child, level + 1)
            }
        }

        /** What [textsWithin] may read under a row: [CONTEXT_DEPTH] levels. */
        private fun rowTexts(m: Mirror, level: Int) {
            if (m.withheld || level >= CONTEXT_DEPTH) return
            for (i in 0 until m.node.childCount) {
                val child = mirror(m.node.getChild(i) ?: continue, m, "${m.path}/$i") ?: continue
                rowTexts(child, level + 1)
            }
        }

        private val labelWords = HashSet<String>()
        private var labelRoom = 0

        /**
         * What [labelOf] reads under the control: its children, and theirs where they are not controls of their own, in
         * the same order. It stops once more words are in than a label keeps: what comes after cannot change the label,
         * and a page of text under a scrolling area is not asked from the app node by node.
         */
        private fun labelParts(m: Mirror) {
            if (m.withheld) return
            for (i in 0 until m.node.childCount) {
                if (labelRoom <= 0) return
                val child = mirror(m.node.getChild(i) ?: continue, m, "${m.path}/$i") ?: continue
                if (child.withheld || child.isOwnControl) continue
                child.words?.let { if (labelWords.add(it)) labelRoom -= it.length + LABEL_SEPARATOR.length }
                labelParts(child)
            }
        }
    }

    /** Limits. */
    companion object {
        /** Nodes read per window at most; a larger tree is [ScreenTree.truncated]. */
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 60

        /** The most characters of a text, description or hint kept on an element; the rest is counted ([ScreenElement.cutChars]) and still fingerprinted. */
        const val MAX_TEXT = ScreenOperator.MAX_TEXT_CHARS
        const val MAX_LABEL = 300
        private const val LABEL_SEPARATOR = " · "
        const val MAX_CONTEXT_TEXT = 80
        const val MAX_CONTEXT_LEVELS = 4
        const val CONTEXT_TEXTS = 2
        const val CONTEXT_DEPTH = 3
        private const val CANCEL_CHECK_EVERY = 25

        /** Past the node limit, at most this many nodes are walked per element the fingerprint may hold (layout nodes are not elements). */
        private const val PASS_NODES_PER_ELEMENT = 4

        /** A title or label as the model sees it: trimmed, at most [MAX_TEXT] characters and then "…", null when blank. */
        fun clean(value: CharSequence?): String? {
            val text = whole(value) ?: return null
            return if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text
        }

        /** [value] trimmed and uncut; null when blank. */
        fun whole(value: CharSequence?): String? = value?.toString()?.trim()?.ifEmpty { null }

        /** An element's text, description or hint as the element carries it: trimmed, the first [MAX_TEXT] characters, null when blank. */
        fun kept(value: CharSequence?): String? = whole(value)?.take(MAX_TEXT)

        /**
         * Whether [node], just refreshed from the app, still is the control [element] was read
         * as: the same kind, text (unless [compareText] is false, after typing into it),
         * description, view id and, where the app sets one, unique id. A list that re-bound the
         * view to another item between the read and the action fails this.
         */
        fun sameIdentity(node: AccessibilityNodeInfo, element: ScreenElement, compareText: Boolean = true): Boolean {
            val role = node.className?.toString()?.substringAfterLast('.')?.ifBlank { null } ?: "View"
            if (role != element.role) return false
            if (compareText && !isWithheld(node) && kept(node.text) != element.text) return false
            if (kept(node.contentDescription) != element.description) return false
            if (node.viewIdResourceName?.ifBlank { null } != element.resourceId) return false
            val key = element.nodeKey
            if (key != null && key.startsWith("id:") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (node.uniqueId?.ifBlank { null }?.let { "id:$it" } != key) return false
            }
            return true
        }

        /** A password field (by flag or input type) or, on Android 14+, a view marked sensitive. */
        fun isWithheld(node: AccessibilityNodeInfo): Boolean {
            if (node.isPassword || isPasswordInput(node.inputType)) return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && node.isAccessibilityDataSensitive
        }

        private fun isPasswordInput(inputType: Int): Boolean {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }
    }
}
