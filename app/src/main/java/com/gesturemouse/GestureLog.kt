package com.gesturemouse

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors

/**
 * Records what the gesture engine did — and, more usefully, what it nearly did.
 *
 * A gesture that misses a threshold by a hair is indistinguishable from no
 * gesture at all once it's gone. From the outside both look like "nothing
 * happened", so without near-misses there's no way to tell "the user didn't
 * try" from "the user tried and the threshold is wrong". Those near-misses are
 * the whole point of this file.
 *
 * Written as JSON lines to the app's external files directory, which `adb pull`
 * can reach without root:
 *   /sdcard/Android/data/com.gesturemouse/files/gestures.jsonl
 */
class GestureLog(context: Context) {

    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    private val started = System.currentTimeMillis()

    /** Approximate size of [file], tracked so rotation doesn't stat on every write. */
    private var bytesWritten = 0L

    val file: File? = try {
        File(context.getExternalFilesDir(null), "gestures.jsonl")
    } catch (e: Exception) {
        Log.e(HidMouse.TAG, "no external files dir", e); null
    }

    init {
        io.execute {
            try {
                // Appending forever would grow without bound on someone else's
                // phone — a few hundred KB per session of heavy use, kept
                // across every session. Start fresh once past the cap; the log
                // is a tuning aid, so only recent history is worth anything.
                if ((file?.length() ?: 0L) > MAX_BYTES) file?.delete()
                bytesWritten = file?.length() ?: 0L
                writer = BufferedWriter(FileWriter(file, true))
                write("session", mapOf(
                    "tapMax" to GestureEngine.TAP_MAX,
                    "clickGap" to GestureEngine.CLICK_GAP,
                    "pinchClose" to GestureEngine.PINCH_CLOSE,
                    "pinchOpen" to GestureEngine.PINCH_OPEN,
                    "dragHold" to GestureEngine.DRAG_HOLD,
                    "sweepGain" to GestureEngine.SWEEP_GAIN,
                    "preciseGain" to GestureEngine.PRECISE_GAIN
                ))
            } catch (e: Exception) {
                Log.e(HidMouse.TAG, "could not open gesture log", e)
            }
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun write(kind: String, data: Map<String, Any>) {
        val w = writer ?: return
        val sb = StringBuilder("{\"t\":").append(System.currentTimeMillis() - started)
        sb.append(",\"k\":\"").append(esc(kind)).append('"')
        for ((k, v) in data) {
            sb.append(",\"").append(esc(k)).append("\":")
            when (v) {
                is Number, is Boolean -> sb.append(v)
                else -> sb.append('"').append(esc(v.toString())).append('"')
            }
        }
        sb.append('}')
        try {
            w.write(sb.toString()); w.newLine()
            bytesWritten += sb.length + 1
            if (bytesWritten > MAX_BYTES) rotate()
        } catch (_: Exception) {
        }
    }

    /** Start the log over once it outgrows [MAX_BYTES]. Runs on the io thread. */
    private fun rotate() {
        try {
            writer?.flush(); writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        try {
            file?.delete()
            writer = BufferedWriter(FileWriter(file, false))
            bytesWritten = 0L
        } catch (e: Exception) {
            Log.e(HidMouse.TAG, "could not rotate gesture log", e)
        }
    }

    fun log(kind: String, data: Map<String, Any> = emptyMap()) {
        io.execute { write(kind, data) }
    }

    fun flush() {
        io.execute { try { writer?.flush() } catch (_: Exception) {} }
    }

    fun close() {
        io.execute {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            writer = null
        }
        io.shutdown()
    }

    companion object {
        /**
         * Cap on the on-disk log. Heavy use writes a few hundred KB an hour,
         * so this holds a decent recent window while staying negligible on a
         * phone that isn't yours.
         */
        const val MAX_BYTES = 512L * 1024L

        /** Monotonic clock the engine and log agree on. */
        fun now() = SystemClock.uptimeMillis()
    }
}
