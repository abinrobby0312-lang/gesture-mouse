package com.gesturemouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequences here are what real keyboards do to a text field. Several are
 * taken from the ways typing used to be lost: an autocorrect that swaps a whole
 * word, a swipe or voice phrase replacing what was there, and deletes that
 * arrive as a shorter string rather than as key presses.
 */
class KeyboardMirrorTest {

    private val m = KeyboardMirror()

    /** Applies a diff the way the app does, returning "<backspaces>|<typed>". */
    private fun step(now: String): String {
        val d = m.update(now)
        return "${d.backspaces}|${d.text}"
    }

    @Test fun typingCharacterByCharacter() {
        assertEquals("0|h", step("h"))
        assertEquals("0|e", step("he"))
        assertEquals("0|y", step("hey"))
    }

    @Test fun composingAWordThenCommittingItSendsNothingTwice() {
        // Gboard composes "hey" then commits the same text
        assertEquals("0|hey", step("hey"))
        assertEquals("0|", step("hey"))
        assertTrue(m.update("hey").isEmpty)
    }

    @Test fun autocorrectSwapsAWholeWord() {
        step("teh")
        // the keyboard replaces "teh" with "the " — however it does that
        assertEquals("2|he ", step("the "))
        assertEquals("the ", m.sent)
    }

    @Test fun swipeOrVoiceReplacesThePhrase() {
        step("see you")
        assertEquals("3|a there", step("see a there"))
    }

    @Test fun backspaceArrivesAsShorterText() {
        step("hello")
        assertEquals("1|", step("hell"))
        assertEquals("4|", step(""))
    }

    @Test fun editInTheMiddleRewritesTheTail() {
        step("hello world")
        // "hello" -> "help", with " world" still after it: "hel" is kept and
        // the other 8 characters are retyped, because the computer's cursor is
        // at the end and can only be walked back
        assertEquals("8|p world", step("help world"))
        assertEquals("help world", m.sent)
    }

    @Test fun replacingEverythingSendsOneCleanSwap() {
        step("abc")
        assertEquals("3|xyz", step("xyz"))
    }

    @Test fun resetMakesTheNextTextTypeInFull() {
        step("hello")
        m.reset()
        assertEquals("", m.sent)
        assertEquals("0|next", step("next"))   // no stray backspaces for cleared text
    }

    @Test fun enterIsJustAnotherCharacter() {
        step("hi")
        assertEquals("0|\n", step("hi\n"))
    }

    @Test fun trimOnlyWhenTheFieldGrowsLong() {
        assertTrue(!m.shouldTrim(10))
        assertTrue(m.shouldTrim(KeyboardMirror.TRIM_AT))
    }

    @Test fun aLongSessionStaysConsistent() {
        // hammer it with a plausible edit stream and check the mirror always
        // matches what the computer would now hold
        var computer = StringBuilder()
        val rng = java.util.Random(42)
        var field = ""
        repeat(400) {
            field = when (rng.nextInt(4)) {
                0 -> field + ('a' + rng.nextInt(26))
                1 -> if (field.isEmpty()) field else field.dropLast(1)
                2 -> if (field.length < 3) field else field.dropLast(2) + "xy"
                else -> if (field.isEmpty()) "word" else field.take(field.length / 2) + "zz"
            }
            val d = m.update(field)
            repeat(d.backspaces) { if (computer.isNotEmpty()) computer.setLength(computer.length - 1) }
            computer.append(d.text)
            assertEquals("after '$field'", field, computer.toString())
        }
    }
}
