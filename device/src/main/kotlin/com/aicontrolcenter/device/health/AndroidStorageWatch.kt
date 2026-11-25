package com.aicontrolcenter.device.health

import android.content.Context
import android.os.storage.StorageManager
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.core.health.StorageWatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The room left for the app's data (Phase 6, R8; WP2 item 10): `StorageManager.getAllocatableBytes` for the volume the
 * app's files are on (API 26), which counts what Android would free from caches for the app. Measured at start, before
 * an objective starts and every 5 minutes while one runs (the callers decide when); below 500 MB the Dashboard warns,
 * below 100 MB running objectives pause and new ones are refused ([StorageLevel]).
 *
 * @param measure the bytes the app could still allocate, or null when Android does not say; injected in tests.
 */
class AndroidStorageWatch(
    context: Context,
    private val measure: () -> Long? = allocatableBytes(context.applicationContext),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StorageWatch {
    private val current = MutableStateFlow(StorageLevel.UNKNOWN)

    override val level: StateFlow<StorageLevel> = current.asStateFlow()

    override suspend fun check(): StorageLevel {
        val bytes = withContext(io) {
            try {
                measure()
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
        return StorageLevel.of(bytes).also { current.value = it }
    }

    companion object {
        /** Android's answer for the volume of the app's files folder; null when there is no storage service. */
        fun allocatableBytes(context: Context): () -> Long? = {
            val manager = context.getSystemService(StorageManager::class.java)
            manager?.getAllocatableBytes(manager.getUuidForPath(context.filesDir))
        }
    }
}
