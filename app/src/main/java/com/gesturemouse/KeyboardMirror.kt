package com.gesturemouse

/**
 * Turns "what the text field says now" into keystrokes for the computer.
 *
 * Phone keyboards don't type one key at a time. They compose a word and revise
 * it, swap it wholesale after autocorrect, swipe or voice input, and delete by
 * editing text around the cursor. There is no reliable way to translate each of
 * those actions into keystrokes one by one — and trying to, by overriding a
 * handful of input methods, is exactly what failed on OnePlus phones: any call
 * that wasn't handled changed the text and sent nothing.
 *
 * So this works from the result instead. After *any* change, [update] compares
 * the whole text with what the computer was last sent and returns the
 * difference: backspaces for what went away, then the characters that arrived.
 *
 * The computer's cursor is always at the end of what we've sent, so only a
 * common *prefix* can be kept. An edit in the middle rewrites everything after
 * it — more keystrokes than strictly needed, but always correct, which matters
 * more.
 */
class KeyboardMirror {

    /** What the computer has been sent, as far as this mirror knows. */
    var sent = ""
        private set

    /** Backspaces to send, then text to type. Either may be empty. */
    data class Diff(val backspaces: Int, val text: String) {
        val isEmpty: Boolean get() = backspaces == 0 && text.isEmpty()
    }

    /** The field now reads [now]; what should the computer receive? */
    fun update(now: String): Diff {
        if (now == sent) return Diff(0, "")
        var p = 0
        val max = minOf(now.length, sent.length)
        while (p < max && now[p] == sent[p]) p++
        val diff = Diff(sent.length - p, now.substring(p))
        sent = now
        return diff
    }

    /**
     * Forget the text without sending anything — after Enter, when the field is
     * cleared to keep it short, or when the computer's cursor has moved
     * somewhere this mirror can no longer describe (arrow keys, Home/End).
     */
    fun reset() {
        sent = ""
    }

    /**
     * True when the field should be cleared to stop it growing all session.
     * Text already sent is of no further use: only the tail can be corrected in
     * practice, and a clear costs nothing as long as nothing is being composed.
     */
    fun shouldTrim(length: Int) = length >= TRIM_AT

    companion object {
        /** Characters in the field before it's worth clearing. */
        const val TRIM_AT = 200
    }
}
