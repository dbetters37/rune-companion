package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.tools.ToolContext

/**
 * The versions of workspace files that a task itself wrote a short while ago, by their exact
 * content. An edit that replaces such a version does not keep it in the trash a second time, so
 * twenty small edits of one file keep what was there before the task's first change once, not
 * twenty copies. Everything else is not in here, and so is kept: the owner's file, another
 * task's or agent's version, a version this task wrote longer ago than [windowMs], and a file
 * changed in any other way since (its content, size or time no longer match).
 *
 * In memory only: after the app restarts, every replaced version is kept again.
 */
class OwnVersions(
    private val windowMs: Long = ConsequentialControls.BULK_WINDOW_MS,
    private val maxEntries: Int = 2_000,
) {
    private class Version(val author: String, val sha256: String, val sizeBytes: Long?, val modifiedAt: Long?, val at: Long)

    private val versions = object : LinkedHashMap<String, Version>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Version>): Boolean = size > maxEntries
    }

    /** Notes that [author] just wrote the version of [path] with this content, size and time. */
    @Synchronized
    fun wrote(author: String, path: RelativePath, sha256: String, sizeBytes: Long?, modifiedAt: Long?, now: Long) {
        versions[path.value] = Version(author, sha256, sizeBytes, modifiedAt, now)
    }

    /** Notes that the version of [path] is no longer one anybody is known to have written. */
    @Synchronized
    fun forget(path: RelativePath) {
        versions.remove(path.value)
    }

    /**
     * True when the file at [path], as [state] found it (with its SHA-256), is exactly the
     * version [author] wrote less than [windowMs] before [now].
     */
    @Synchronized
    internal fun isOwn(author: String, path: RelativePath, state: EntryState, now: Long): Boolean {
        val version = versions[path.value] ?: return false
        if (now - version.at !in 0 until windowMs) {
            if (now - version.at >= windowMs) versions.remove(path.value)
            return false
        }
        return version.author == author && state.exists && !state.isFolder && state.sha256 != null && state.sha256 == version.sha256 &&
            state.sizeBytes == version.sizeBytes && state.modifiedAt == version.modifiedAt
    }

    companion object {
        /** Who a call writes as: the agent in its task, or in its conversation when it works outside a task. */
        fun author(context: ToolContext): String = context.agent.id.value + "|" + when {
            context.taskId != null -> "task:" + context.taskId!!.value
            context.conversationId != null -> "chat:" + context.conversationId!!.value
            else -> "-"
        }
    }
}
