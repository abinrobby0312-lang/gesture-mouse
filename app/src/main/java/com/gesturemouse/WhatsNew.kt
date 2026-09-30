package com.gesturemouse

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * The "here's what changed" note, shown once after the app updates.
 *
 * Only after an *update*: a fresh install has the walkthrough, and someone
 * meeting the app for the first time doesn't care what used to be broken. The
 * version last seen is remembered, so the note appears once and then never
 * again — and nothing is shown for a release with nothing worth saying.
 *
 * Someone who skipped a release sees everything they missed, in one dialog.
 */
object WhatsNew {

    private const val PREFS = "gesturemouse"
    private const val KEY_LAST_SEEN = "lastSeenVersion"

    /** versionCode of the first public build (1.7.0); older installs are assumed to be on it. */
    private const val FIRST_PUBLIC = 8

    /** [since] is the versionCode that introduced it. */
    private class Note(val since: Int, val headline: String, val body: String)

    /**
     * What to say, oldest first. Keep it to what someone would actually notice;
     * a release with only internal changes belongs nowhere in here.
     */
    private val NOTES = listOf(
        Note(
            9, "Keyboard bugs: squashed 🪳",
            "Typing on the computer didn't always come through. Your reports found it, and it's fixed — " +
                    "whatever your keyboard throws at it: autocorrect, swiping, voice, the lot.\n\n" +
                    "The only rodent around here is the Gesture Mouse. 🐭"
        ),
        // This one is also a disclosure: Android doesn't ask again when an app
        // gains internet access on update, so people are told here instead.
        Note(
            13, "New: Report a bug 🐞",
            "Settings → Help & contact → Report a bug. It sends us a log so we can fix things — " +
                    "only when you tap Send, and you see exactly what's in it first. Computer names and " +
                    "Bluetooth addresses are scrubbed out.\n\n" +
                    "That's why the app now has internet access. It's the only thing it uses it for."
        )
    )

    /**
     * Shows what's new if this launch is the first since an update. Returns
     * true when a dialog went up.
     */
    fun showIfUpdated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        val stored = prefs.getInt(KEY_LAST_SEEN, 0)
        prefs.edit().putInt(KEY_LAST_SEEN, current).apply()

        if (stored >= current) return false
        // No record means one of two very different things: a brand new install,
        // or one from before this note existed — everyone updating from 1.8.0
        // or earlier, the people it is for. The walkthrough flag tells them
        // apart: it's only set once the app has been used.
        val from = when {
            stored > 0 -> stored
            TutorialDialog.hasBeenSeen(context) -> FIRST_PUBLIC
            else -> return false
        }

        val fresh = NOTES.filter { it.since in (from + 1)..current }
        if (fresh.isEmpty()) return false

        AlertDialog.Builder(context)
            .setTitle(if (fresh.size == 1) fresh[0].headline else "What's new")
            .setMessage(
                if (fresh.size == 1) fresh[0].body
                else fresh.joinToString("\n\n") { it.headline + "\n" + it.body }
            )
            .setPositiveButton("Nice", null)
            .show()
        return true
    }

    /** A fresh install has seen this version by definition — see [showIfUpdated]. */
    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LAST_SEEN, BuildConfig.VERSION_CODE).apply()
    }
}
