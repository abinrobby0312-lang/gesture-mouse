package com.gesturemouse

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * The "here's what changed" note, shown once after the app updates.
 *
 * Only after an *update*: a fresh install has the walkthrough, and someone
 * meeting the app for the first time doesn't care what used to be broken. The
 * version last seen is remembered, so the note appears once and then never
 * again — and nothing is shown at all for a version with nothing worth saying.
 */
object WhatsNew {

    private const val PREFS = "gesturemouse"
    private const val KEY_LAST_SEEN = "lastSeenVersion"

    private data class Note(val title: String, val body: String, val dismiss: String)

    /**
     * What to say, per version. Keep it to what someone would actually notice;
     * a version with only internal changes belongs nowhere in here.
     */
    private fun noteFor(versionCode: Int): Note? = when (versionCode) {
        9, 10, 11, 12 -> Note(
            "Keyboard bugs: squashed 🪳",
            "Typing on the computer didn't always come through. Your reports found it, and it's fixed — " +
                    "whatever your keyboard throws at it: autocorrect, swiping, voice, the lot.\n\n" +
                    "The only rodent around here is the Gesture Mouse. 🐭",
            "Nice"
        )
        else -> null
    }

    /**
     * Shows the note if this launch is the first since an update. Returns true
     * when a dialog went up, so the caller can hold anything else back.
     */
    fun showIfUpdated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        val lastSeen = prefs.getInt(KEY_LAST_SEEN, 0)
        prefs.edit().putInt(KEY_LAST_SEEN, current).apply()

        if (lastSeen >= current) return false
        // No record at all means one of two very different things: a brand new
        // install, or an install from before this note existed — which is
        // everyone updating from 1.8.0 or earlier, the very people the note is
        // for. The walkthrough flag tells them apart: it is only set once the
        // app has been used.
        if (lastSeen == 0 && !TutorialDialog.hasBeenSeen(context)) return false
        val note = noteFor(current) ?: return false

        AlertDialog.Builder(context)
            .setTitle(note.title)
            .setMessage(note.body)
            .setPositiveButton(note.dismiss, null)
            .show()
        return true
    }

    /** A fresh install has seen this version by definition — see [showIfUpdated]. */
    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LAST_SEEN, BuildConfig.VERSION_CODE).apply()
    }
}
