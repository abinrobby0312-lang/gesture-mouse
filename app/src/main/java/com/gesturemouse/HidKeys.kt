package com.gesturemouse

/**
 * Keyboard half of the HID device: what to press, and in what order, to make a
 * host open a URL.
 *
 * Pure data and pure functions — no Android, no Bluetooth — so the whole
 * "which keys does opening a link actually involve" question is unit-testable
 * without a phone or a host in the loop.
 *
 * ## Why typing at all
 *
 * The host has nothing installed and doesn't know this app exists; it sees a
 * generic HID device. There is no channel to say "please open this URL". The
 * only vocabulary shared with the host is keystrokes, so opening a link means
 * driving the launcher the user already has — Run, Spotlight, a terminal — and
 * typing the address into it.
 *
 * ## Layout assumption
 *
 * HID transmits *physical key positions*, not characters. The host applies its
 * own keyboard layout on top. This table is US QWERTY, so on a layout that
 * moves the punctuation — AZERTY, QWERTZ, Dvorak — symbols like `:` and `/`
 * will come out as something else. Letters and digits survive most Latin
 * layouts; the symbols are the fragile part, which is why [isTypable] exists
 * and why short URLs are worth preferring.
 */
object HidKeys {

    /**
     * What the crossed-palms gesture opens.
     *
     * Lives here rather than in the fragment so a unit test can check every
     * character of it survives [stroke] — a URL with an untypable character in
     * it would otherwise fail silently on a host, mid-type.
     *
     * Every character is typed individually, so shorter is more reliable: the
     * `?si=…` tail is YouTube share tracking and can be deleted without
     * changing which video opens.
     */
    const val EASTER_EGG_URL = "https://youtu.be/eHoIUNY-bG4?si=dPZQX9y5SVj8o8rm"

    // modifier bits, as they sit in byte 0 of a keyboard report
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08          // Windows key / Command

    const val KEY_ENTER = 0x28
    const val KEY_SPACE = 0x2C
    const val KEY_R = 0x15
    const val KEY_T = 0x17

    /** One keystroke: a usage code plus whatever modifiers are held with it. */
    data class Stroke(val usage: Int, val mods: Int = 0)

    /**
     * A step in an "open this URL" script. Waits matter as much as the
     * keystrokes: a launcher window needs time to appear and take focus, and
     * typing into it before it exists silently drops the whole URL.
     */
    sealed interface Step {
        data class Key(val usage: Int, val mods: Int = 0) : Step
        data class Text(val text: String) : Step
        data class Wait(val ms: Long) : Step
    }

    /** Punctuation that sits on an unshifted key in US QWERTY. */
    private val PLAIN = mapOf(
        ' ' to KEY_SPACE,
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
        ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
    )

    /** Characters reached by holding shift, mapped to the key underneath. */
    private val SHIFTED = mapOf(
        '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
        ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38,
        '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
        '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27
    )

    /** The keystroke for one character, or null if this table can't produce it. */
    fun stroke(c: Char): Stroke? = when {
        c in 'a'..'z' -> Stroke(0x04 + (c - 'a'))
        c in 'A'..'Z' -> Stroke(0x04 + (c - 'A'), MOD_SHIFT)
        c in '1'..'9' -> Stroke(0x1E + (c - '1'))
        c == '0' -> Stroke(0x27)
        PLAIN.containsKey(c) -> Stroke(PLAIN.getValue(c))
        SHIFTED.containsKey(c) -> Stroke(SHIFTED.getValue(c), MOD_SHIFT)
        else -> null
    }

    /** Every character maps to a key we can actually press. */
    fun isTypable(text: String) = text.all { stroke(it) != null }

    enum class HostOs(val label: String) {
        WINDOWS("Windows"),
        MACOS("macOS"),
        LINUX("Linux");

        /**
         * Keystrokes that make this host open [url] in its default browser.
         *
         * Each one drives a launcher that's present out of the box, because
         * anything app-specific (Ctrl+T for a new tab, say) depends on what
         * happens to be focused at the time.
         */
        fun openUrlScript(url: String): List<Step> = when (this) {
            // Win+R opens Run, which hands a bare URL to the default browser.
            WINDOWS -> listOf(
                Step.Key(KEY_R, MOD_GUI),
                Step.Wait(700),
                Step.Text(url),
                Step.Wait(120),
                Step.Key(KEY_ENTER)
            )
            // Cmd+Space opens Spotlight, which does the same.
            MACOS -> listOf(
                Step.Key(KEY_SPACE, MOD_GUI),
                Step.Wait(800),
                Step.Text(url),
                Step.Wait(200),
                Step.Key(KEY_ENTER)
            )
            // No desktop-agnostic launcher accepts a URL, so go through a
            // terminal and xdg-open. Ctrl+Alt+T is the GNOME/Ubuntu default;
            // on a desktop that doesn't bind it, nothing happens at all.
            LINUX -> listOf(
                Step.Key(KEY_T, MOD_CTRL or MOD_ALT),
                Step.Wait(1400),
                Step.Text("xdg-open $url"),
                Step.Wait(120),
                Step.Key(KEY_ENTER)
            )
        }
    }
}
