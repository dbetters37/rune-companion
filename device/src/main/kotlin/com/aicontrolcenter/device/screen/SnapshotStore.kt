package com.aicontrolcenter.device.screen

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget

/**
 * Numbers what was read and keeps the last [keep] snapshots, so a
 * [ScreenTarget.Numbered] names exactly what that model saw, whatever other agents read since.
 *
 * Ids follow the wall clock (the larger of "last id + 1" and now, in milliseconds), so an id from
 * before an app restart never names a snapshot taken after it: an unknown id is simply stale.
 */
internal class SnapshotStore(private val clock: Clock, private val keep: Int = KEPT_SNAPSHOTS) {
    private val lock = Any()
    private var lastId = 0L
    private val kept = LinkedHashMap<Long, ScreenSnapshot>()

    /**
     * Numbers [tree]'s visible elements from 1 in reading order, keeps at most [maxElements] of
     * them and stores the snapshot.
     */
    fun record(tree: ScreenTree, maxElements: Int = Int.MAX_VALUE): Recorded = synchronized(lock) {
        val now = clock.now()
        val id = maxOf(lastId + 1, now)
        lastId = id
        val visible = tree.visible
        val shown = visible.take(maxElements.coerceAtLeast(1))
        val elements = shown.mapIndexed { i, entry -> entry.element.copy(snapshotId = id, number = i + 1) }
        val snapshot = ScreenSnapshot(
            id = id,
            packageName = tree.packageName,
            windowTitle = tree.windowTitle,
            elements = elements,
            withheld = tree.withheld,
            truncated = tree.truncated || visible.size > shown.size,
            capturedAt = now,
        )
        kept[id] = snapshot
        while (kept.size > keep) kept.remove(kept.keys.first())
        Recorded(snapshot, shown.zip(elements).toMap())
    }

    fun get(id: Long): ScreenSnapshot? = synchronized(lock) { kept[id] }

    /**
     * [tree]'s visible elements as a snapshot that is neither numbered for later reference nor
     * kept (id 0): what an assessment shows the owner and compares again before acting. It
     * carries the tree's fingerprint of the whole window, and how many elements that covers
     * beyond the ones listed.
     */
    fun unrecorded(tree: ScreenTree, maxElements: Int): ScreenSnapshot {
        val visible = tree.visible
        val shown = visible.take(maxElements.coerceAtLeast(1))
        return ScreenSnapshot(
            id = 0,
            packageName = tree.packageName,
            windowTitle = tree.windowTitle,
            elements = shown.mapIndexed { i, entry -> entry.element.copy(snapshotId = 0, number = i + 1) },
            withheld = tree.withheld,
            truncated = tree.truncated || visible.size > shown.size,
            capturedAt = clock.now(),
            windowDigest = tree.digest,
            unlisted = if (tree.digest != null) (tree.elementCount - shown.size).coerceAtLeast(0) else 0,
        )
    }

    /** A recorded snapshot and the numbered element of each entry it shows. */
    class Recorded(val snapshot: ScreenSnapshot, private val numbered: Map<ScreenEntry, ScreenElement>) {
        /** [entry] as numbered in [snapshot]; null if the snapshot left it out. */
        fun elementOf(entry: ScreenEntry): ScreenElement? = numbered[entry]
    }

    /** Limits. */
    companion object {
        /** As in the testkit's `FakeScreenOperator`. */
        const val KEPT_SNAPSHOTS = 16
    }
}
