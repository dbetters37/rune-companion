package com.aicontrolcenter.device.screen

import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.aicontrolcenter.core.operator.ScreenBounds
import com.aicontrolcenter.core.operator.ScreenElement

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
 */
internal class ScreenReader(
    private val maxNodes: Int = MAX_NODES,
    private val maxDepth: Int = MAX_DEPTH,
) {
    fun read(root: AccessibilityNodeInfo, packageName: String, windowTitle: String?, checkCancelled: () -> Unit = {}): ScreenTree {
        val walk = Walk(checkCancelled)
        val top = walk.mirror(root, null, "", 0)
        val entries = ArrayList<ScreenEntry>()
        val byMirror = HashMap<Mirror, ScreenEntry>()
        val title = windowTitle?.let(::clean)
        fun build(m: Mirror) {
            if (m.isElement) {
                val clickTarget = if (m.clickable || m.editable) null else m.ancestors().firstOrNull { it.clickable }?.let(byMirror::get)
                val scrollAncestor = m.ancestors().firstOrNull { it.scrollable }?.let(byMirror::get)
                val entry = ScreenEntry(element(m, packageName, title), m.node, m.withheld, clickTarget, scrollAncestor)
                byMirror[m] = entry
                entries += entry
            }
            for (child in m.children) build(child)
        }
        top?.let(::build)
        return ScreenTree(packageName, title, entries, walk.withheld, walk.truncated)
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
        return parts.joinToString(" · ").take(MAX_LABEL).ifBlank { null }
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

    /** One node's facts, copied once. */
    private class Mirror(val node: AccessibilityNodeInfo, val parent: Mirror?, val path: String, val withheld: Boolean) {
        val children = ArrayList<Mirror>(0)
        val role: String = node.className?.toString()?.substringAfterLast('.')?.ifBlank { null } ?: "View"
        val text: String? = if (withheld) null else clean(node.text)
        val description: String? = clean(node.contentDescription)
        val hint: String? = if (withheld) null else clean(node.hintText)
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
            if (withheld || !(editable || checkable)) {
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
    }

    private inner class Walk(private val checkCancelled: () -> Unit) {
        var visited = 0
        var withheld = 0
        var truncated = false

        fun mirror(node: AccessibilityNodeInfo, parent: Mirror?, path: String, depth: Int): Mirror? {
            if (visited >= maxNodes) {
                truncated = true
                return null
            }
            if (!node.isVisibleToUser) return null
            visited++
            if (visited % CANCEL_CHECK_EVERY == 0) checkCancelled()
            val hidden = isWithheld(node)
            val mirror = Mirror(node, parent, path, hidden)
            if (hidden) {
                withheld++
                return mirror
            }
            val count = node.childCount
            if (count > 0 && depth >= maxDepth) {
                truncated = true
                return mirror
            }
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                mirror(child, mirror, "$path/$i", depth + 1)?.let(mirror.children::add)
            }
            return mirror
        }
    }

    /** Limits. */
    companion object {
        /** Nodes read per window at most; a larger tree is [ScreenTree.truncated]. */
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 60
        const val MAX_TEXT = 500
        const val MAX_LABEL = 300
        const val MAX_CONTEXT_TEXT = 80
        const val MAX_CONTEXT_LEVELS = 4
        const val CONTEXT_TEXTS = 2
        const val CONTEXT_DEPTH = 3
        private const val CANCEL_CHECK_EVERY = 25

        /** Text as the model sees it: trimmed, at most [MAX_TEXT] characters, null when blank. */
        fun clean(value: CharSequence?): String? {
            val text = value?.toString()?.trim() ?: return null
            if (text.isEmpty()) return null
            return if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text
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
