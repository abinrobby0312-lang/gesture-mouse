package com.gesturemouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportTest {

    @Test fun aShortLogIsKeptWhole() {
        val log = "line 1\nline 2\n"
        assertEquals(log, BugReport.tail(log))
    }

    @Test fun aLongLogKeepsItsEndAndStartsOnALine() {
        val lines = (1..20_000).joinToString("\n") { "line $it padding padding" }
        val out = BugReport.tail(lines)
        assertTrue(out.length <= BugReport.LOG_MAX)
        assertTrue("the end is where the problem is", out.endsWith("line 20000 padding padding"))
        assertTrue("no half line at the start", out.startsWith("line "))
    }

    @Test fun theCapFitsUnderTheDatabaseLimit() {
        // the table refuses anything over 64,000 characters
        assertTrue(BugReport.LOG_MAX <= 64_000)
    }

    @Test fun emailChecks() {
        assertTrue(BugReport.isValidEmail("a@b.co"))
        assertTrue(BugReport.isValidEmail("first.last+tag@example.com"))
        assertFalse(BugReport.isValidEmail("nope"))
        assertFalse(BugReport.isValidEmail("a@b"))
        assertFalse(BugReport.isValidEmail("a b@c.de"))
        assertFalse(BugReport.isValidEmail("x".repeat(250) + "@b.co"))
    }

    @Test fun thePreviewShowsWhatIsSent() {
        val s = BugReport.Snapshot("1.9.0 (13)", "14 (API 34)", "Google Pixel 6", "the log")
        val p = s.preview()
        for (part in listOf("1.9.0 (13)", "14 (API 34)", "Google Pixel 6", "the log")) assertTrue(part, p.contains(part))
    }
}
