package nz.fishingnz.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Conditions recorded with a catch, looked up after it is saved. Every field is optional. */
data class CatchConditions(
    /** "Incoming", "Outgoing", "Around high" or "Around low". */
    val tidePhase: String? = null,
    /** "Incoming · 2 h after low (LINZ Raglan)". */
    val tide: String? = null,
    /** "Breezy: 18 km/h, gusts to 26 km/h, from the SW". */
    val wind: String? = null,
    val waves: String? = null,
    val waterTemperature: String? = null,
    val moon: String? = null,
    val tideRange: String? = null
) {
    val isEmpty: Boolean get() = listOf(tidePhase, tide, wind, waves, waterTemperature, moon).all { it == null }
}

/**
 * One fish (or several of the same species) in the catch log. [updatedAtEpochSeconds] and [deleted] let account sync
 * merge devices; a deleted entry stays as a tombstone until it has been synced. [photoFile] is a file name in this
 * device's private storage and never leaves it. The iPhone app has the same fields in `CatchLog.swift`.
 */
data class CatchEntry(
    val id: String,
    val caughtAtEpochSeconds: Long,
    val species: String,
    val lengthCm: Double? = null,
    val kept: Boolean = false,
    val count: Int = 1,
    val placeName: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rulesAreaId: String? = null,
    val notes: String = "",
    val photoFile: String? = null,
    val conditions: CatchConditions = CatchConditions(),
    val updatedAtEpochSeconds: Long = caughtAtEpochSeconds,
    val deleted: Boolean = false
)

/** A size check against one MPI rule row. [meets] is null when the rule cannot be compared safely. */
data class SizeVerdict(val rule: String, val meets: Boolean?, val text: String)

object CatchLog {
    private val zone = ZoneId.of("Pacific/Auckland")
    const val MAX_LENGTH_CM = 400.0

    fun visible(entries: List<CatchEntry>): List<CatchEntry> = entries.filterNot { it.deleted }.sortedByDescending { it.caughtAtEpochSeconds }

    /**
     * Compares [lengthCm] with each matching MPI rule row. Only a single plain number is compared; a range, several
     * numbers or a tail width points to MPI instead. Rules in millimetres are converted.
     */
    fun sizeVerdicts(lengthCm: Double, rules: List<FishRuleMatch>): List<SizeVerdict> = rules.mapNotNull { rule ->
        val raw = rule.minimumSize?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val label = rule.minimumSizeLabel.orEmpty()
        val numbers = Regex("\\d+(?:\\.\\d+)?").findAll(raw).map { it.value.toDouble() }.toList()
        val name = rule.species.replace(Regex("\\s*[*†‡§]+\\s*"), " ").trim()
        val shown = formatCm(lengthCm)
        if (numbers.size != 1 || label.contains("tail", true) || raw.contains("tail", true)) {
            return@mapNotNull SizeVerdict(name, null, "Check MPI for the minimum size of $name.")
        }
        val millimetres = label.contains("mm", true) || raw.contains("mm", true)
        val minimumCm = if (millimetres) numbers.single() / 10 else numbers.single()
        val minimum = formatCm(minimumCm)
        if (lengthCm >= minimumCm) SizeVerdict(name, true, "$shown meets the $minimum minimum for $name.")
        else SizeVerdict(name, false, "$shown is under the $minimum minimum for $name. Release it.")
    }

    /** Fish of [species] kept on the New Zealand day of [at], from entries other than [excludingId]. */
    fun keptOnDay(entries: List<CatchEntry>, species: String, at: Instant, excludingId: String? = null): Int {
        val day = at.atZone(zone).toLocalDate()
        val key = species.trim().lowercase(Locale.ROOT)
        return entries.filter { !it.deleted && it.kept && it.id != excludingId && it.species.trim().lowercase(Locale.ROOT) == key &&
            Instant.ofEpochSecond(it.caughtAtEpochSeconds).atZone(zone).toLocalDate() == day }.sumOf { it.count }
    }

    /** Plain-word patterns once there are at least three catches. Describes the log only; it predicts nothing. */
    fun insights(entries: List<CatchEntry>): List<String> {
        val list = visible(entries)
        val fish = list.sumOf { it.count }
        if (list.size < 3) return emptyList()
        val lines = mutableListOf<String>()
        val kept = list.filter { it.kept }.sumOf { it.count }
        lines += "$fish fish logged · $kept kept, ${fish - kept} released"
        list.groupBy { it.species.trim() }.mapValues { (_, items) -> items.sumOf { it.count } }
            .maxByOrNull { it.value }?.let { (species, count) -> lines += "Most caught: $species ($count)" }
        fun share(label: String, keyOf: (CatchEntry) -> String?) {
            val known = list.mapNotNull { entry -> keyOf(entry)?.let { it to entry.count } }
            val total = known.sumOf { it.second }
            if (total < 3) return
            val (top, count) = known.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.maxBy { it.value }
            if (count * 2 > total) lines += "$label: mostly ${top.lowercase(Locale.ROOT)} ($count of $total fish)"
        }
        share("Tide") { it.conditions.tidePhase }
        share("Time of day") { entry -> timeOfDay(Instant.ofEpochSecond(entry.caughtAtEpochSeconds)) }
        share("Tide range") { it.conditions.tideRange }
        return lines
    }

    fun timeOfDay(at: Instant): String = when (at.atZone(zone).hour) {
        in 4..7 -> "Early morning"
        in 8..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }

    /** Spreadsheet-ready export of the visible entries, newest first. Photos stay on the phone. */
    fun csv(entries: List<CatchEntry>): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        fun cell(value: Any?): String {
            val text = value?.toString().orEmpty()
            return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
        val header = listOf("Date and time (NZ)", "Species", "Length (cm)", "Kept", "Count", "Place", "Latitude", "Longitude",
            "Tide", "Wind", "Waves", "Water temperature", "Moon", "Notes")
        return (listOf(header.joinToString(",")) + visible(entries).map { e ->
            listOf(Instant.ofEpochSecond(e.caughtAtEpochSeconds).atZone(zone).format(time), e.species, e.lengthCm?.let { formatNumber(it) },
                if (e.kept) "Kept" else "Released", e.count, e.placeName, e.latitude, e.longitude, e.conditions.tide, e.conditions.wind,
                e.conditions.waves, e.conditions.waterTemperature, e.conditions.moon, e.notes).joinToString(",") { cell(it) }
        }).joinToString("\n") + "\n"
    }

    /** "Snapper · 32 cm · kept" or "2 × Kahawai · released". */
    fun headline(entry: CatchEntry): String = listOfNotNull(
        (if (entry.count > 1) "${entry.count} × " else "") + entry.species,
        entry.lengthCm?.let(::formatCm),
        if (entry.kept) "kept" else "released"
    ).joinToString(" · ")

    fun formatCm(value: Double): String = "${formatNumber(value)} cm"

    private fun formatNumber(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else String.format(Locale.US, "%.1f", value)

    fun day(at: Instant): LocalDate = at.atZone(zone).toLocalDate()

    /** Common recreational species, offered before the area's rules load. */
    val commonSpecies = listOf("Snapper", "Kahawai", "Gurnard", "Trevally", "Kingfish", "Blue cod", "Tarakihi", "John dory",
        "Blue moki", "Red cod", "Flatfish", "Butterfish", "Hāpuku", "Yellow-eyed mullet", "Rock lobster", "Pāua", "Scallops")
}
