package com.gesturemouse

/**
 * Takes out of a log what shouldn't leave the phone in a bug report.
 *
 * The app's own log names the computers it talks to — "Connecting to <name>…",
 * often something like "Sam's MacBook Pro" — and prints their full Bluetooth
 * addresses on most connection lines. Both identify a person's hardware, and a
 * bug report needs neither: what matters is *which* device did what, not which
 * device it was.
 *
 * So each distinct address becomes `device-1`, `device-2`… and each name
 * becomes `computer-1`… or `this-phone`, the same label every time within one
 * report. A reader can still follow "device-2 dropped the link, then device-2
 * reconnected" without learning whose it is.
 *
 * Pure logic — no Android types — so it is unit-tested.
 */
class LogScrubber(computerNames: Collection<String>, phoneName: String? = null) {

    companion object {
        /** Names shorter than this are left alone: replacing "PC" would mangle ordinary words. */
        const val MIN_NAME = 3

        private val MAC = Regex("\\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\\b")
    }

    private class Rule(val pattern: Regex, val label: String)

    private val nameRules: List<Rule>
    private val addresses = LinkedHashMap<String, String>()

    init {
        val phone = phoneName?.trim()?.takeIf { it.length >= MIN_NAME }
        val computers = computerNames.map { it.trim() }
            .filter { it.length >= MIN_NAME && !it.equals(phone, ignoreCase = true) }
            .distinctBy { it.lowercase() }

        val labelled = ArrayList<Pair<String, String>>()
        if (phone != null) labelled += phone to "this-phone"
        computers.forEachIndexed { i, name -> labelled += name to "computer-${i + 1}" }

        // longest first, so "Sam's MacBook Pro" goes before "Sam's"
        nameRules = labelled.sortedByDescending { it.first.length }
            .map { (name, label) -> Rule(Regex(Regex.escape(name), RegexOption.IGNORE_CASE), label) }
    }

    fun scrub(text: String): String {
        var out = text
        for (r in nameRules) out = r.pattern.replace(out, r.label)
        return MAC.replace(out) { m ->
            addresses.getOrPut(m.value.lowercase()) { "device-${addresses.size + 1}" }
        }
    }
}
