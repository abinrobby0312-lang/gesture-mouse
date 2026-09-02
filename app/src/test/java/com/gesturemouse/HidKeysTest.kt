package com.gesturemouse

import com.gesturemouse.HidKeys.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard map, checked against the thing it actually has to type.
 *
 * A wrong usage code doesn't crash anything — it silently types the wrong
 * character on someone else's computer, which is exactly the kind of bug that
 * only shows up when a host is in front of you. Hence checking the codes
 * against the HID usage tables here instead.
 */
class HidKeysTest {

    // ---- the payload ----------------------------------------------------------

    @Test fun theEasterEggUrlCanActuallyBeTyped() {
        val bad = HidKeys.EASTER_EGG_URL.filter { HidKeys.stroke(it) == null }
        assertEquals("no key for: $bad", "", bad)
        assertTrue(HidKeys.isTypable(HidKeys.EASTER_EGG_URL))
    }

    // ---- the map --------------------------------------------------------------

    @Test fun lettersMapToTheirUsageCodes() {
        assertEquals(0x04, HidKeys.stroke('a')!!.usage)
        assertEquals(0x1D, HidKeys.stroke('z')!!.usage)
        assertEquals(0, HidKeys.stroke('a')!!.mods)
    }

    @Test fun capitalsAreTheSameKeyWithShift() {
        val lower = HidKeys.stroke('h')!!
        val upper = HidKeys.stroke('H')!!
        assertEquals(lower.usage, upper.usage)
        assertEquals(HidKeys.MOD_SHIFT, upper.mods)
    }

    @Test fun digitsMapToTheNumberRow() {
        assertEquals(0x1E, HidKeys.stroke('1')!!.usage)
        assertEquals(0x26, HidKeys.stroke('9')!!.usage)
        // zero sits at the end of the row, not the start
        assertEquals(0x27, HidKeys.stroke('0')!!.usage)
    }

    @Test fun urlPunctuationLandsOnTheRightKeys() {
        // the characters a URL cannot do without
        assertEquals(0x38, HidKeys.stroke('/')!!.usage)
        assertEquals(0x37, HidKeys.stroke('.')!!.usage)
        assertEquals(0x2D, HidKeys.stroke('-')!!.usage)
        assertEquals(0x2E, HidKeys.stroke('=')!!.usage)

        // ':' is shift+';' and '?' is shift+'/'
        assertEquals(0x33, HidKeys.stroke(':')!!.usage)
        assertEquals(HidKeys.MOD_SHIFT, HidKeys.stroke(':')!!.mods)
        assertEquals(0x38, HidKeys.stroke('?')!!.usage)
        assertEquals(HidKeys.MOD_SHIFT, HidKeys.stroke('?')!!.mods)
    }

    @Test fun charactersOutsideTheMapAreRejectedRatherThanGuessed() {
        assertNull(HidKeys.stroke('é'))
        assertNull(HidKeys.stroke('€'))
        assertNull(HidKeys.stroke('\n'))
        assertTrue(!HidKeys.isTypable("café.com"))
    }

    @Test fun everyPrintableAsciiUrlCharacterIsCovered() {
        // RFC 3986 leaves these as the characters a URL may contain
        val allowed = "abcdefghijklmnopqrstuvwxyz" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;="
        for (c in allowed) assertNotNull("no key for '$c'", HidKeys.stroke(c))
    }

    // ---- the scripts ----------------------------------------------------------

    @Test fun windowsUsesRunThenEnter() {
        val steps = HidKeys.HostOs.WINDOWS.openUrlScript("https://x.com")
        val first = steps.first() as Step.Key
        assertEquals(HidKeys.KEY_R, first.usage)
        assertEquals(HidKeys.MOD_GUI, first.mods)
        assertEquals(HidKeys.KEY_ENTER, (steps.last() as Step.Key).usage)
    }

    @Test fun macUsesSpotlight() {
        val first = HidKeys.HostOs.MACOS.openUrlScript("https://x.com").first() as Step.Key
        assertEquals(HidKeys.KEY_SPACE, first.usage)
        assertEquals(HidKeys.MOD_GUI, first.mods)
    }

    @Test fun linuxGoesThroughATerminal() {
        val steps = HidKeys.HostOs.LINUX.openUrlScript("https://x.com")
        val first = steps.first() as Step.Key
        assertEquals(HidKeys.KEY_T, first.usage)
        assertEquals(HidKeys.MOD_CTRL or HidKeys.MOD_ALT, first.mods)
        val typed = steps.filterIsInstance<Step.Text>().single().text
        assertTrue("should shell out to xdg-open, got: $typed", typed.startsWith("xdg-open "))
    }

    @Test fun everyScriptTypesTheUrlAndWaitsForItsLauncher() {
        val url = "https://youtu.be/abc"
        for (os in HidKeys.HostOs.entries) {
            val steps = os.openUrlScript(url)
            val typed = steps.filterIsInstance<Step.Text>().single().text
            assertTrue("${os.label} must type the url, got: $typed", typed.contains(url))

            // a launcher needs time to appear; typing into a window that isn't
            // there yet drops the whole URL on the floor
            val firstWait = steps.indexOfFirst { it is Step.Wait }
            val firstText = steps.indexOfFirst { it is Step.Text }
            assertTrue("${os.label} must wait before typing", firstWait in 0 until firstText)
        }
    }

    @Test fun everyScriptIsTypableEndToEnd() {
        for (os in HidKeys.HostOs.entries) {
            for (step in os.openUrlScript(HidKeys.EASTER_EGG_URL)) {
                if (step is Step.Text) assertTrue(os.label, HidKeys.isTypable(step.text))
            }
        }
    }
}
