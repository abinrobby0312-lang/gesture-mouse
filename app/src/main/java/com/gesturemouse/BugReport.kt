package com.gesturemouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Sending a bug report — the *only* thing in this app that touches the network.
 *
 * That is deliberate and enforced: `NetworkGuardTest` fails the build if any
 * other source file uses a network API. The promise made to users is that the
 * app connects to the internet for this one purpose, when they tap Send, after
 * being shown what is in the report. Nothing here runs on its own.
 *
 * What goes in a report: the app and Android versions, the phone's make and
 * model, an optional note and email typed by the person, and the tail of the
 * app's own log with computer names and Bluetooth addresses scrubbed out
 * ([LogScrubber]). Never typed text — the keyboard logs only counts — and
 * never anything from the camera.
 *
 * Reports go to a table that can be added to but not read with the key shipped
 * here (see site/supabase/schema.sql), so they're readable only by the
 * maintainers, from the database dashboard.
 */
object BugReport {

    // The project URL and *publishable* key: safe to ship, by design. The
    // database lets this key insert into one table and do nothing else.
    private const val ENDPOINT = "https://ruhwscwgveyzizpruxyf.supabase.co/rest/v1/bug_reports"
    private const val KEY = "sb_publishable_DJY6ZHam2TEsLjbjf19M-w_fqKVSfqu"

    /** Characters of log kept; the database refuses more than 64,000. */
    const val LOG_MAX = 60_000

    private const val LINES = "3000"
    private const val READ_TIMEOUT_MS = 6_000L

    /** Everything that will be sent apart from what the person types. */
    data class Snapshot(
        val appVersion: String,
        val androidVersion: String,
        val device: String,
        val log: String
    ) {
        /** Exactly what the person is shown before they send. */
        fun preview(): String =
            "App: $appVersion\nAndroid: $androidVersion\nPhone: $device\n\n$log"
    }

    /**
     * Gathers the report. Slow (it reads the system log), so call off the main
     * thread. [status] is the one-line connection state shown in the app.
     */
    @SuppressLint("MissingPermission")
    fun snapshot(context: Context, status: String): Snapshot {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val computers = try {
            adapter?.bondedDevices?.mapNotNull { it.name }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()   // no Bluetooth permission: nothing to scrub either
        }
        val phone = try { adapter?.name } catch (_: SecurityException) { null }

        val scrubbed = LogScrubber(computers, phone).scrub("status: $status\n\n${readLog()}")
        return Snapshot(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            log = tail(scrubbed)
        )
    }

    /** Keeps the end of the log — where the problem is — cut at a line boundary. */
    internal fun tail(text: String): String {
        if (text.length <= LOG_MAX) return text
        return text.takeLast(LOG_MAX).substringAfter('\n')
    }

    /**
     * The app's own log: everything it logged itself, plus warnings and errors
     * from anything else in its process. Android only lets an app read its own
     * log, so this can't see other apps whatever it asks for.
     */
    private fun readLog(): String = try {
        val proc = ProcessBuilder(
            "logcat", "-d", "-b", "main", "-b", "crash", "-v", "threadtime", "-t", LINES,
            "${HidMouse.TAG}:I", "*:W"
        ).redirectErrorStream(true).start()

        // read as it arrives: a full pipe would otherwise stall logcat
        val out = StringBuffer()
        val reader = Thread {
            try { proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
            catch (_: Exception) { }
        }
        reader.start()
        reader.join(READ_TIMEOUT_MS)
        if (reader.isAlive) proc.destroy()
        out.toString().ifBlank { "(the system returned no log)" }
    } catch (e: Exception) {
        "(couldn't read the log: ${e.javaClass.simpleName})"
    }

    /** True if [s] is plausibly an email address; the database checks again. */
    fun isValidEmail(s: String) =
        s.length <= 254 && Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(s)

    /**
     * Sends the report. Blocks — call off the main thread. Returns true only
     * when the server confirms it was stored.
     */
    fun send(snapshot: Snapshot, description: String?, email: String?): Boolean {
        val body = JSONObject()
            .put("app_version", snapshot.appVersion)
            .put("android_version", snapshot.androidVersion)
            .put("device", snapshot.device)
            .put("description", description?.trim()?.takeIf { it.isNotEmpty() }?.take(2000) ?: JSONObject.NULL)
            .put("email", email?.trim()?.takeIf { it.isNotEmpty() }?.take(254) ?: JSONObject.NULL)
            .put("log", snapshot.log)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val conn = URL(ENDPOINT).openConnection() as HttpsURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("apikey", KEY)
            conn.setRequestProperty("Authorization", "Bearer $KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.outputStream.use { it.write(body) }
            conn.responseCode == 201
        } finally {
            conn.disconnect()
        }
    }
}
