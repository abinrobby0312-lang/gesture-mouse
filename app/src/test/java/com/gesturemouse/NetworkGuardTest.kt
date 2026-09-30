package com.gesturemouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the privacy promise checkable.
 *
 * The app tells users it connects to the internet for one thing: sending a bug
 * report they chose to send. This fails the build if any other source file
 * reaches for a network API, so adding a second use means a deliberate edit to
 * this test — visible in review — rather than slipping in with a library call.
 *
 * It covers this project's own code. Third-party libraries are handled in the
 * manifest instead (see the note there on MediaPipe's telemetry backend).
 */
class NetworkGuardTest {

    private val sources = File("src/main/java")

    private val networkApis = listOf(
        "java.net.", "javax.net.", "HttpURLConnection", "HttpsURLConnection",
        "okhttp", "android.webkit.WebView", "android.net.http", "DatagramSocket",
        "java.nio.channels.SocketChannel", "retrofit"
    )

    @Test fun theSourcesAreWhereWeThinkTheyAre() {
        // a wrong working directory would make the real test pass vacuously
        assertTrue("expected ${sources.absolutePath}", sources.isDirectory)
        assertTrue(sources.walkTopDown().count { it.extension == "kt" } > 10)
    }

    @Test fun onlyBugReportTouchesTheNetwork() {
        val offenders = sources.walkTopDown()
            .filter { it.extension == "kt" || it.extension == "java" }
            .filter { it.name != "BugReport.kt" }
            .mapNotNull { f ->
                val hit = networkApis.firstOrNull { f.readText().contains(it) }
                if (hit != null) "${f.name} uses $hit" else null
            }
            .toList()
        assertEquals("only BugReport.kt may use the network:\n${offenders.joinToString("\n")}",
            emptyList<String>(), offenders)
    }

    @Test fun bugReportDoesUseItSoTheCheckIsNotVacuous() {
        assertTrue(File(sources, "com/gesturemouse/BugReport.kt").readText().contains("HttpsURLConnection"))
    }
}
