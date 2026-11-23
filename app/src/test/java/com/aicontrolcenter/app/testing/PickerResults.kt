package com.aicontrolcenter.app.testing

import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for Android's pickers: each launch is recorded with its input and answered at once
 * with [next] (a picked folder's or document's Uri), or with nothing when [next] is null, as when
 * the owner backs out of the picker.
 */
class PickerResults : ActivityResultRegistry() {
    /** What the next launch returns. */
    @Volatile var next: Any? = null

    /** The input of every launch, in order. */
    val launched = CopyOnWriteArrayList<Any?>()

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launched += input
        dispatchResult(requestCode, next)
    }
}
