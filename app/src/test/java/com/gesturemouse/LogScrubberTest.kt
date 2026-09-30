package com.gesturemouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines here are the shapes the app's real log takes; see HidMouse. */
class LogScrubberTest {

    @Test fun addressesBecomeStableLabels() {
        val s = LogScrubber(emptyList())
        val out = s.scrub(
            "connect attempt 1/4 to DC:21:5C:F3:AB:E1 returned true\n" +
                    "onConnectionStateChanged device=DC:21:5C:F3:AB:E1 state=0\n" +
                    "incoming from 00:42:38:CD:01:44 — dropping our attempt on DC:21:5C:F3:AB:E1"
        )
        assertEquals(
            "connect attempt 1/4 to device-1 returned true\n" +
                    "onConnectionStateChanged device=device-1 state=0\n" +
                    "incoming from device-2 — dropping our attempt on device-1",
            out
        )
    }

    @Test fun addressMatchingIgnoresCase() {
        val out = LogScrubber(emptyList()).scrub("dc:21:5c:f3:ab:e1 then DC:21:5C:F3:AB:E1")
        assertEquals("device-1 then device-1", out)
    }

    @Test fun namesBecomeLabels() {
        val s = LogScrubber(listOf("MARK-IV", "Sam's MacBook Pro"), phoneName = "Abin's Pixel")
        val out = s.scrub(
            "state=CONNECTING msg=Connecting to MARK-IV… (1/4)\n" +
                    "state=CONNECTED msg=Sam's MacBook Pro\n" +
                    "Abin's Pixel is discoverable"
        )
        assertFalse(out.contains("MARK-IV"))
        assertFalse(out.contains("MacBook"))
        assertFalse(out.contains("Pixel"))
        assertTrue(out.contains("Connecting to computer-1… (1/4)"))
        assertTrue(out.contains("msg=computer-2"))
        assertTrue(out.contains("this-phone is discoverable"))
    }

    @Test fun theLongerNameWinsWhenOneContainsTheOther() {
        val s = LogScrubber(listOf("Sam", "Sam's MacBook Pro"))
        val out = s.scrub("connected to Sam's MacBook Pro")
        // replaced whole, not as "computer-x's MacBook Pro"
        assertEquals("connected to computer-2", out)
    }

    @Test fun namesAreMatchedWithoutRegardToCase() {
        assertEquals("computer-1 / computer-1",
            LogScrubber(listOf("Mark-IV")).scrub("MARK-IV / mark-iv"))
    }

    @Test fun namesWithRegexCharactersAreTakenLiterally() {
        val s = LogScrubber(listOf("C++ Box (1)", "a.b"))
        assertEquals("on computer-1 and computer-2 but not axb",
            s.scrub("on C++ Box (1) and a.b but not axb"))
    }

    @Test fun veryShortNamesAreLeftAlone() {
        // "PC" would otherwise eat every "PC" inside other words
        assertEquals("the PC and a BPCD", LogScrubber(listOf("PC")).scrub("the PC and a BPCD"))
    }

    @Test fun thePhonesOwnNameIsNotAlsoAComputer() {
        val out = LogScrubber(listOf("Pixel Dock", "Abin's Pixel"), phoneName = "Abin's Pixel")
            .scrub("Abin's Pixel next to Pixel Dock")
        assertEquals("this-phone next to computer-1", out)
    }

    @Test fun anAlreadyRedactedAddressIsNotTouched() {
        // the system's own logs print addresses as XX:XX:XX:XX:01:44
        val line = "peer XX:XX:XX:XX:01:44 disconnected"
        assertEquals(line, LogScrubber(emptyList()).scrub(line))
    }

    @Test fun textWithNothingToScrubComesBackUnchanged() {
        val line = "keyboard: -1 +3 char(s)\nstate=WAITING msg=Not connected"
        assertEquals(line, LogScrubber(listOf("MARK-IV")).scrub(line))
    }

    @Test fun nothingIdentifyingSurvivesARealisticLog() {
        val names = listOf("MARK-IV", "Sam's MacBook Pro")
        val log = """
            09-30 01:10:49 I GMouse: state=CONNECTING msg=Connecting to MARK-IV… (1/4)
            09-30 01:10:49 I GMouse: connect attempt 1/4 to DC:21:5C:F3:AB:E1 returned true
            09-30 01:10:55 W GMouse: giving up on 00:42:38:CD:01:44 — bonded but won't accept HID
            09-30 01:10:55 I GMouse: state=STALE_BOND msg=Sam's MacBook Pro is paired but won't accept the mouse
        """.trimIndent()
        val out = LogScrubber(names, "Abin's Pixel").scrub(log)
        for (secret in listOf("MARK-IV", "MacBook", "DC:21", "00:42", "CD:01")) {
            assertFalse("'$secret' survived:\n$out", out.contains(secret, ignoreCase = true))
        }
        assertTrue(out.contains("giving up on device-2"))
    }
}
