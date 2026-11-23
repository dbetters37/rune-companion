package com.aicontrolcenter.device.screen

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/** One node action a test tree received: which node (by its test name), which action, and the text argument. */
internal data class Performed(val node: String, val action: Int, val text: CharSequence? = null)

/**
 * Builds accessibility node trees the way an app's views would expose them. Android hands the
 * service copies of nodes, so actions are recorded through a listener every copy shares
 * ([performed]), and [refuse] makes a node's actions fail.
 */
internal class NodeBuilder(
    private val packageName: String,
    var className: String,
    /** How the test refers to this node in [Performed]. */
    var name: String,
    private val log: MutableList<Performed>,
) {
    var text: String? = null
    var description: String? = null
    var hint: String? = null
    var viewId: String? = null
    var clickable = false
    var editable = false
    var scrollable = false
    var checkable = false
    var checked = false
    var enabled = true
    var visible = true
    var password = false
    var sensitive = false
    var inputType = 0
    var uniqueId: String? = null
    var stateDescription: String? = null
    var bounds = Rect(0, 0, 200, 60)
    var collectionItem = false
    var collection = false
    var refuse = false
    var stale = false

    /** Like Android's own text fields: the keyboard action is offered only once the field is focused (ACTION_FOCUS). */
    var imeWhenFocused = false
    var labeledBy: NodeBuilder? = null
    val actions = mutableListOf<AccessibilityAction>()
    val children = mutableListOf<NodeBuilder>()

    /** The node this builder made (the original; the service sees copies). */
    lateinit var built: AccessibilityNodeInfo

    fun child(className: String = "android.view.View", name: String = className, block: NodeBuilder.() -> Unit = {}): NodeBuilder =
        NodeBuilder(packageName, className, name, log).apply(block).also(children::add)

    fun button(text: String?, name: String = text ?: "button", block: NodeBuilder.() -> Unit = {}) = child("android.widget.Button", name) {
        this.text = text
        clickable = true
        actions += AccessibilityAction.ACTION_CLICK
        block()
    }

    fun label(text: String, name: String = text, block: NodeBuilder.() -> Unit = {}) = child("android.widget.TextView", name) {
        this.text = text
        block()
    }

    fun field(name: String, block: NodeBuilder.() -> Unit = {}) = child("android.widget.EditText", name) {
        editable = true
        clickable = true
        actions += AccessibilityAction.ACTION_CLICK
        actions += AccessibilityAction.ACTION_SET_TEXT
        block()
    }

    fun list(name: String = "list", block: NodeBuilder.() -> Unit = {}) = child("androidx.recyclerview.widget.RecyclerView", name) {
        scrollable = true
        collection = true
        actions += AccessibilityAction.ACTION_SCROLL_FORWARD
        actions += AccessibilityAction.ACTION_SCROLL_BACKWARD
        block()
    }

    fun row(name: String, block: NodeBuilder.() -> Unit = {}) = child("android.widget.LinearLayout", name) {
        clickable = true
        collectionItem = true
        actions += AccessibilityAction.ACTION_CLICK
        block()
    }

    fun build(): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo()
        node.packageName = packageName
        node.className = className
        node.text = text
        node.contentDescription = description
        node.hintText = hint
        node.viewIdResourceName = viewId
        node.isClickable = clickable
        node.isEditable = editable
        node.isScrollable = scrollable
        node.isCheckable = checkable
        @Suppress("DEPRECATION")
        node.isChecked = checked
        node.isEnabled = enabled
        node.isVisibleToUser = visible
        node.isPassword = password
        node.isAccessibilityDataSensitive = sensitive
        node.inputType = inputType
        node.uniqueId = uniqueId
        node.stateDescription = stateDescription
        node.setBoundsInScreen(bounds)
        if (collection) node.collectionInfo = AccessibilityNodeInfo.CollectionInfo(children.size, 1, false)
        if (collectionItem) node.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo(0, 1, 0, 1, false, false)
        actions.forEach(node::addAction)
        val shadow = shadowOf(node)
        shadow.setRefreshReturnValue(!stale)
        if (imeWhenFocused) node.addAction(AccessibilityAction.ACTION_FOCUS)
        shadow.setOnPerformActionListener { action, arguments: Bundle? ->
            log += Performed(name, action, arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            if (imeWhenFocused && action == AccessibilityNodeInfo.ACTION_FOCUS && !refuse) {
                // The app's view, now focused, reports itself anew: the next read sees the keyboard action.
                built.isFocused = true
                built.addAction(AccessibilityAction.ACTION_IME_ENTER)
            }
            !refuse
        }
        labeledBy?.let { shadow.setLabeledBy(it.built) }
        for (child in children) shadow.addChild(child.build())
        built = node
        return node
    }
}

/** A window of [packageName] whose content [content] describes. */
internal class TestWindow(
    val packageName: String,
    val title: String? = null,
    val id: Int = 7,
    val type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    var active: Boolean = true,
    val bounds: Rect = Rect(0, 0, 1080, 2400),
    val log: MutableList<Performed> = CopyOnWriteArrayList(),
    content: NodeBuilder.() -> Unit = {},
) {
    val root: NodeBuilder = NodeBuilder(packageName, "android.widget.FrameLayout", "root", log).apply {
        bounds = this@TestWindow.bounds
        content()
    }

    /** Every node by its test name. */
    fun node(name: String): NodeBuilder = find(root, name) ?: error("No node named $name")

    private fun find(node: NodeBuilder, name: String): NodeBuilder? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(it, name) }

    /** Builds the nodes and the window Android would report. */
    fun build(): AccessibilityWindowInfo {
        val window = AccessibilityWindowInfo()
        val shadow = shadowOf(window)
        shadow.setId(id)
        shadow.setType(type)
        shadow.setActive(active)
        shadow.setFocused(active)
        shadow.setTitle(title)
        shadow.setBoundsInScreen(bounds)
        shadow.setRoot(root.build())
        return window
    }
}

/** A system window without content of interest (status bar, shade, keyboard...). */
internal fun systemWindow(id: Int, bounds: Rect, type: Int = AccessibilityWindowInfo.TYPE_SYSTEM, packageName: String = "com.android.systemui"): TestWindow =
    TestWindow(packageName, id = id, type = type, active = false, bounds = bounds)
