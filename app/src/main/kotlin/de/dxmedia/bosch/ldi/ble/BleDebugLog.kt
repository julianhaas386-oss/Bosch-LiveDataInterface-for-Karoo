package de.dxmedia.bosch.ldi.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * In-memory ring buffer of recent BLE events for on-device diagnostics (issue #4 follow-up).
 *
 * Always forwards to [android.util.Log] (tag [TAG]); additionally captures timestamped lines
 * into a bounded buffer **only while [enabled]**. The buffer is published via [entries] so the
 * debug screen can render it live. Lost on process death — this is a live diagnostic, not a log file.
 */
object BleDebugLog {

    const val TAG = "BleManager"
    const val MAX_ENTRIES = 300

    /** Toggled from settings. When false, [add] only forwards to logcat. */
    @Volatile
    var enabled: Boolean = false

    private val buffer = ArrayDeque<String>(MAX_ENTRIES)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun i(msg: String) = add('I', msg, null) { Log.i(TAG, msg) }
    fun w(msg: String) = add('W', msg, null) { Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) = add('E', msg, t) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private inline fun add(level: Char, msg: String, t: Throwable?, logcat: () -> Unit) {
        logcat()
        if (!enabled) return
        val line = buildString {
            append(timestamp())
            append(' ').append(level).append(' ')
            append(msg)
            if (t != null) {
                append(" — ").append(t.javaClass.simpleName)
                t.message?.let { append(": ").append(it) }
            }
        }
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(line)
            _entries.value = buffer.toList()
        }
    }

    private fun timestamp(): String {
        val now = System.currentTimeMillis()
        val totalSeconds = now / 1000
        val mm = (totalSeconds / 60) % 60
        val ss = totalSeconds % 60
        val ms = now % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", mm, ss, ms)
    }
}
