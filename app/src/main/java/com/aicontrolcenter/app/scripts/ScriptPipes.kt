package com.aicontrolcenter.app.scripts

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream

/**
 * The pipes between the app and the script process (Phase 6, rule 11). Plain pipes, read and written as streams of
 * length-prefixed frames ([ScriptFrames]); never the status of a reliable pipe, which the sender's own close takes away
 * when it hands an end over in a call (spike F-D: "Remote side is dead" in 10 of 10 runs).
 *
 * Every place that makes, reads, writes or closes an end goes through this, so that the tests can stand in a real
 * pipe for the file Robolectric makes of `createPipe` (a reader of that file sees its end before the writer is done).
 */
internal interface ScriptPipes {
    /** A new pipe. */
    fun open(): Pipe

    /** A stream reading [end]; closing the stream closes [end]. */
    fun reader(end: ParcelFileDescriptor): InputStream

    /** A stream writing [end]; closing the stream closes [end]. */
    fun writer(end: ParcelFileDescriptor): OutputStream

    /** Closes this process's copy of [end]. A thread blocked reading or writing it is woken with an error. */
    fun close(end: ParcelFileDescriptor)

    /**
     * [end] was handed to the other process as an argument of a call that has returned: the other process has its
     * own copy now, and this one is closed.
     */
    fun handedOver(end: ParcelFileDescriptor) = close(end)

    /** The two ends of a pipe: whatever is written to [write] is read from [read]. */
    class Pipe(val read: ParcelFileDescriptor, val write: ParcelFileDescriptor)

    /** Android's pipes. */
    object Android : ScriptPipes {
        override fun open(): Pipe = ParcelFileDescriptor.createPipe().let { Pipe(it[0], it[1]) }

        override fun reader(end: ParcelFileDescriptor): InputStream = ParcelFileDescriptor.AutoCloseInputStream(end)

        override fun writer(end: ParcelFileDescriptor): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(end)

        // Android's close wakes a thread that is blocked on the same descriptor (libcore's AsynchronousCloseMonitor).
        override fun close(end: ParcelFileDescriptor) {
            runCatching { end.close() }
        }
    }
}
