package nz.fishingnz.app.data

import org.json.JSONObject
import java.text.Normalizer

/** One illustration of a species, such as "Large male". [file] is its name under the service's /species/ folder. */
data class SpeciesPicture(val id: String, val caption: String, val file: String, val sha256: String)

/**
 * A species on the rules page: its names, group (finfish, shark, shellfish or crayfish) and pictures. [combined] marks a
 * common catch with no MPI limit of its own, which counts toward the area's combined finfish limit.
 */
data class SpeciesInfo(val id: String, val name: String, val maori: String?, val scientific: String, val group: String,
                       val match: List<String>, val pictures: List<SpeciesPicture>, val combined: Boolean = false)

/**
 * The species pictures and the MPI rule names each covers, from the service's rules manifest or the copy built into the
 * app (`tools/build_offline_rules.py`). The iPhone app reads the same catalogue in `RulesOffline.swift`.
 */
data class SpeciesCatalog(val version: String, val note: String, val species: List<SpeciesInfo>) {
    private val matches = species.flatMap { info -> info.match.map { normalize(it) to info } }.sortedByDescending { it.first.length }

    /**
     * The species an MPI rule row is about: the longest listed name the row starts with as a whole word (a plural "s"
     * allowed), ignoring case and macrons.
     */
    fun forRule(ruleSpecies: String): SpeciesInfo? {
        val name = normalize(ruleSpecies)
        fun wordEnds(at: Int) = name.getOrNull(at)?.isLetter() != true
        return matches.firstOrNull { (prefix, _) ->
            name.startsWith(prefix) && (wordEnds(prefix.length) || (name.getOrNull(prefix.length) == 's' && wordEnds(prefix.length + 1)))
        }?.second
    }

    val pictures: List<SpeciesPicture> get() = species.flatMap { it.pictures }.distinctBy { it.file }

    /** Common catches with no rule of their own among [ruleSpecies], the area's rule rows. */
    fun commonCatches(ruleSpecies: List<String>): List<SpeciesInfo> {
        val ruled = ruleSpecies.mapNotNull { forRule(it)?.id }.toSet()
        return species.filter { it.combined && it.id !in ruled }
    }

    companion object {
        fun parse(json: JSONObject): SpeciesCatalog {
            require(json.optInt("schema_version") == 1) { "Unknown species catalogue." }
            val items = json.getJSONArray("species")
            return SpeciesCatalog(json.getString("version"), json.optString("note"), (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val match = item.getJSONArray("match")
                val pictures = item.getJSONArray("pictures")
                SpeciesInfo(item.getString("id"), item.getString("name"), item.optString("maori").takeIf { it.isNotBlank() },
                    item.optString("scientific"), item.optString("group", "finfish"),
                    (0 until match.length()).map(match::getString),
                    (0 until pictures.length()).map { position ->
                        val picture = pictures.getJSONObject(position)
                        SpeciesPicture(picture.getString("id"), picture.optString("caption"), picture.getString("file"), picture.getString("sha256"))
                    }, item.optBoolean("combined"))
            })
        }

        /** Lower case without macrons or other accents, dashes as spaces: "Pāua – Yellowfoot" becomes "paua   yellowfoot". */
        fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('–', ' ').replace('—', ' ').trim()
    }
}
