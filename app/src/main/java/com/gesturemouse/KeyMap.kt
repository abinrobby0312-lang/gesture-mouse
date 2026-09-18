package com.gesturemouse

/**
 * Characters and editing keys to HID keyboard usages.
 *
 * A HID keyboard sends key *positions*, not characters: the computer turns them
 * back into text through its own keyboard layout. This table is the US layout,
 * so letters, digits and most punctuation come out right everywhere, but a
 * computer set to a layout that moves symbols around (French, German, …) will
 * read some of those keys as different symbols. That's inherent to being a
 * keyboard, not something the phone can correct.
 */
object KeyMap {

    const val MOD_SHIFT = 0x02

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val DELETE = 0x4C
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    /** One keystroke: modifier bits and the key's usage ID. */
    data class Stroke(val modifiers: Int, val usage: Int)

    // unshifted and shifted characters on the same US key
    private const val PLAIN = "1234567890-=[]\\;',./`"
    private const val SHIFTED = "!@#$%^&*()_+{}|:\"<>?~"
    private val PUNCT_USAGE = intArrayOf(
        0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,  // 1..0
        0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x36, 0x37, 0x38, 0x35
    )

    /**
     * Typographic characters phone keyboards substitute on their own (smart
     * quotes, the dash from a double hyphen). None of them exist on a US
     * keyboard, so they're sent as the plain character the user meant.
     */
    private val SUBSTITUTES = mapOf(
        '‘' to "'", '’' to "'", '‚' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"",
        '–' to "-", '—' to "-", '…' to "...",
        ' ' to " "   // non-breaking space
    )

    /** Keystrokes for [text]; characters with no US key are left out. */
    fun strokes(text: CharSequence): List<Stroke> {
        val out = ArrayList<Stroke>(text.length)
        for (c in text) {
            val sub = SUBSTITUTES[c]
            if (sub != null) sub.forEach { s -> forChar(s)?.let(out::add) }
            else forChar(c)?.let(out::add)
        }
        return out
    }

    fun forChar(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0, 0x04 + (c - 'a'))
        in 'A'..'Z' -> Stroke(MOD_SHIFT, 0x04 + (c - 'A'))
        ' ' -> Stroke(0, 0x2C)
        '\n' -> Stroke(0, ENTER)
        '\t' -> Stroke(0, TAB)
        else -> {
            val i = PLAIN.indexOf(c)
            val j = SHIFTED.indexOf(c)
            when {
                i >= 0 -> Stroke(0, PUNCT_USAGE[i])
                j >= 0 -> Stroke(MOD_SHIFT, PUNCT_USAGE[j])
                else -> null
            }
        }
    }
}
