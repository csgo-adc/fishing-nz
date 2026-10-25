package nz.fishingnz.app.ui

import nz.fishingnz.app.data.FishingRulesPage

/** One species row of an MPI rules table, simplified for the rules page. */
internal data class RuleSpeciesRow(val id: String, val species: String, val facts: List<Pair<String, String>>, val note: String?, val hasFootnote: Boolean) {
    /** The single minimum length or size with its unit, such as 30.0 to "cm"; null when there is none or it varies. */
    val minimum: Pair<Double, String>? get() = facts.firstOrNull { (label, _) -> label == "Minimum length" || label == "Minimum size" }?.second
        ?.let { Regex("^(\\d+(?:\\.\\d+)?) (cm|mm)$").find(it) }?.let { it.groupValues[1].toDouble() to it.groupValues[2] }
    /** The daily limit as shown, such as "7" or "No take"; null when the row has none. */
    val dailyLimit: String? get() = facts.firstOrNull { (label, _) -> label.startsWith("Daily limit") }?.second
}

internal fun ruleFactLabel(header: String): String? {
    val text = header.lowercase()
    return when {
        text.contains("daily limit") && text.contains("outside fiord") -> "Daily limit · outer area"
        text.contains("daily limit") && text.contains("the fiords") -> "Daily limit · inner fiords"
        text.contains("daily limit") && text.contains("auckland coromandel") -> "Daily limit · Auckland/Coromandel"
        text.contains("daily limit") -> "Daily limit"
        text.contains("fish length") -> "Minimum length"
        text.contains("min size") || text.contains("minimum size") -> "Minimum size"
        text.contains("tail width") -> "Minimum tail width"
        else -> null
    }
}

internal fun ruleSpeciesRows(page: FishingRulesPage): List<RuleSpeciesRow> = page.tables.flatMapIndexed { tableIndex, saved ->
    // One of MPI's tables (Southland) has its header row last; read it as the header.
    val table = if (saved.firstOrNull()?.firstOrNull()?.contains("species", true) != true && saved.lastOrNull()?.firstOrNull()?.trim()?.endsWith("species", true) == true)
        listOf(saved.last()) + saved.dropLast(1) else saved
    val headers = table.firstOrNull().orEmpty()
    if (headers.size < 2 || !headers.first().contains("species", ignoreCase = true)) return@flatMapIndexed emptyList()
    val factColumns = headers.indices.drop(1).mapNotNull { index -> ruleFactLabel(headers[index])?.let { index to it } }
    if (factColumns.isEmpty()) return@flatMapIndexed emptyList()
    table.drop(1).mapIndexedNotNull { rowIndex, row ->
        val rawSpecies = row.firstOrNull()?.trim().orEmpty()
        if (rawSpecies.isEmpty() || rawSpecies.startsWith("*") || rawSpecies.length > 180 || rawSpecies.equals("Finfish species", true)) return@mapIndexedNotNull null
        val qualifier = if (row.size == headers.size && headers.getOrNull(1)?.equals(headers.first(), true) == true)
            row.getOrNull(1)?.trim().orEmpty() else ""
        val species = cleanMpiRuleText(rawSpecies).let { name ->
            if (qualifier.isNotBlank() && !qualifier.equals(rawSpecies, true))
                "$name — ${cleanMpiRuleText(qualifier)}" else name
        }
        if (species.isEmpty()) return@mapIndexedNotNull null
        val notes = mutableListOf<String>()
        val hasFootnote = row.any(::hasMpiFootnoteMarker)
        if (hasFootnote) notes += "MPI footnote applies. Read the full condition on the official page."
        if (rawSpecies.contains("refer to map", true)) notes += "Check the exact area boundary on MPI."
        val facts = if (row.size != headers.size) {
            notes += "This saved row cannot be matched safely to its columns. Check the official MPI limit."
            listOf("Area-specific rule" to "See MPI")
        } else factColumns.mapNotNull { (index, label) ->
            val rawValue = row.getOrNull(index)?.trim().orEmpty()
            if (rawValue.isBlank() || rawValue in listOf("—", "–", "-", "none")) return@mapNotNull null
            val clean = cleanMpiRuleText(rawValue)
            val value = when {
                clean.contains("No take allowed", true) -> "No take"
                clean.contains("See below", true) -> { notes += "Check the area-specific rule on MPI."; "See MPI" }
                label == "Minimum tail width" -> clean
                Regex("\\d+").findAll(clean).count() > 1 -> { notes += "This value covers multiple species or subareas; check MPI."; "Varies — see MPI" }
                label.startsWith("Minimum") && clean.matches(Regex("^\\d+.*")) -> {
                    val number = Regex("^\\d+").find(clean)!!.value
                    val remainder = clean.removePrefix(number).trim()
                    if (remainder.isNotEmpty()) notes += remainder
                    "$number ${if (headers[index].contains("(cm)", true)) "cm" else "mm"}"
                }
                else -> clean
            }
            label to value
        }
        if (facts.isEmpty()) return@mapIndexedNotNull null
        RuleSpeciesRow("$tableIndex-$rowIndex", species, facts, notes.distinct().takeIf { it.isNotEmpty() }?.joinToString(" "), hasFootnote)
    }
}


/** The answer to "Check my catch": whether a length meets the row's minimum, in words. */
internal data class CatchCheckResult(val meets: Boolean, val text: String)

private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)

/** Checks [length], in the row's own unit, against its single minimum; null when the row has none. */
internal fun checkCatch(row: RuleSpeciesRow, length: Double): CatchCheckResult? {
    val (minimum, unit) = row.minimum ?: return null
    if (length <= 0) return null
    val shown = "${number(length)} $unit"
    val limit = row.dailyLimit?.takeIf { it.toIntOrNull() != null }?.let { " It counts toward the daily limit of $it." }.orEmpty()
    return if (length >= minimum) CatchCheckResult(true, "$shown meets the ${number(minimum)} $unit minimum.$limit")
    else CatchCheckResult(false, "$shown is under the ${number(minimum)} $unit minimum. Release it carefully.")
}

/** The area's combined daily finfish limit, such as 20, when its text states one. */
internal fun combinedFinfishLimit(page: FishingRulesPage): Int? = page.sections.firstNotNullOfOrNull { section ->
    Regex("combined daily bag limit of\\s+(\\d+)\\s+finfish", RegexOption.IGNORE_CASE).find(section.text)?.groupValues?.get(1)?.toIntOrNull()
}

/** The area's "All others (combined)" shellfish limit, such as 50, when every daily limit in that row is the same number. */
internal fun otherShellfishLimit(rows: List<RuleSpeciesRow>): Int? = rows.firstOrNull { it.species.startsWith("All others", ignoreCase = true) }
    ?.facts?.filter { it.first.startsWith("Daily limit") }?.map { it.second }?.distinct()?.singleOrNull()?.toIntOrNull()

/** The rules page's species groups; [id] matches the species catalogue's group. */
internal enum class SpeciesGroup(val label: String, val id: String?) {
    POPULAR("Popular", null), FINFISH("Finfish", "finfish"), SHARKS("Sharks and rays", "shark"),
    SHELLFISH("Shellfish", "shellfish"), CRAYFISH("Crayfish", "crayfish"), ALL("All", null)
}

/** The species most people look up first, in this order. */
internal val popularSpecies = listOf("snapper", "blue cod", "kingfish", "kahawai", "red gurnard", "gurnard", "tarakihi", "trevally",
    "red or spiny rock lobster", "pāua", "paua", "scallops", "cockle")

internal fun popularRows(rows: List<RuleSpeciesRow>): List<RuleSpeciesRow> {
    fun rank(row: RuleSpeciesRow) = popularSpecies.indexOfFirst { row.species.startsWith(it, ignoreCase = true) }
    return rows.filter { rank(it) >= 0 }.sortedBy(::rank).take(10)
}
