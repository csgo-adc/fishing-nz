package nz.fishingnz.app.ui

import nz.fishingnz.app.model.FishRuleMatch

/** MPI uses asterisks and a few other suffix symbols as footnote references. */
private val suffixPlus = Regex("(?<=[\\p{L}\\p{N}])\\+(?![\\p{L}\\p{N}])")
private val suffixHash = Regex("(?<=[\\p{L}\\p{N}])#(?![\\p{L}\\p{N}])")
private val trailingBullet = Regex("\\s*•\\s*$")

internal fun hasMpiFootnoteMarker(value: String): Boolean =
    value.any { it == '*' || it == '†' || it == '‡' } || value.contains("^+") ||
        trailingBullet.containsMatchIn(value) || suffixPlus.containsMatchIn(value) || suffixHash.containsMatchIn(value)

internal fun hasMpiFootnote(rule: FishRuleMatch): Boolean =
    hasMpiFootnoteMarker(rule.species) || rule.dailyLimit?.let(::hasMpiFootnoteMarker) == true ||
        rule.minimumSize?.let(::hasMpiFootnoteMarker) == true || rule.minimumSizeLabel?.let(::hasMpiFootnoteMarker) == true ||
        rule.details.any { hasMpiFootnoteMarker(it.label) || hasMpiFootnoteMarker(it.value) }

internal fun cleanMpiRuleText(value: String): String = value
    .replace(Regex("\\(Fisheries Management Area\\s*(\\d+)[^)]*\\)", RegexOption.IGNORE_CASE), "(FMA $1)")
    .replace(Regex("\\[PDF[^]]*]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\*+"), "")
    .replace("^+", "")
    .replace(suffixPlus, "")
    .replace(suffixHash, "")
    .replace("†", "")
    .replace("‡", "")
    .replace(trailingBullet, "")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '–', '-', ':')

/** A cell with several quantities describes more than one species or subarea, not one simple limit. */
internal fun cleanMpiLimitValue(value: String): String = cleanMpiRuleText(value).let { clean ->
    if (Regex("\\d+").findAll(clean).count() > 1) "Varies — see MPI" else clean
}

/** Identification prose can arrive with Markdown emphasis, but Compose Text shows its delimiters literally. */
internal fun cleanIdentificationText(value: String): String = value
    .replace(Regex("(?m)^[ \\t]*#{1,6}[ \\t]+"), "")
    .replace(Regex("(?m)^[ \\t]*[-*][ \\t]+"), "• ")
    .replace(Regex("\\*{1,2}"), "")
    .replace(Regex("__(.*?)__"), "$1")
    .replace("`", "")
    .trim()
