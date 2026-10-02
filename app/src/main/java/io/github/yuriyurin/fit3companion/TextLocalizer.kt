package io.github.yuriyurin.fit3companion

/** Presentation-only bridge for legacy Russian labels and status messages.
 * Wire data, persisted values, user text and external names are never rewritten.
 * New UI strings should use Android string resources directly.
 */
class TextLocalizer(pairs: List<Pair<String, String>>) {
    private val placeholder = Regex("\\{(\\d+)\\}")
    private val exact = pairs.filterNot { placeholder.containsMatchIn(it.first) }.toMap()
    private val templates = pairs.filter { placeholder.containsMatchIn(it.first) }.map { (ru, en) ->
        val matches = placeholder.findAll(ru).toList()
        var offset = 0
        val pattern = buildString {
            append("^")
            matches.forEach { match ->
                append(Regex.escape(ru.substring(offset, match.range.first)))
                append("(.*?)")
                offset = match.range.last + 1
            }
            append(Regex.escape(ru.substring(offset)))
            append("$")
        }
        Template(Regex(pattern, RegexOption.DOT_MATCHES_ALL), en,
            matches.map { it.groupValues[1].toInt() }, ru.length - matches.sumOf { it.value.length })
    }.sortedByDescending { it.specificity }

    private data class Template(val pattern: Regex, val english: String,
                                val arguments: List<Int>, val specificity: Int)

    fun translate(value: String): String = translate(value, 0)

    private fun translate(value: String, depth: Int): String {
        if (depth > 4 || value.length > 8192 || !value.any { it in '\u0400'..'\u04ff' }) return value
        exact[value]?.let { return it }
        // Optional suffix next to a numeric placeholder has no delimiter between
        // the two arguments in the legacy compact step summary.
        val partial = " (неполная сумма)"
        if (value.endsWith(partial) && value != partial) {
            return translate(value.removeSuffix(partial), depth + 1) + (exact[partial] ?: partial)
        }
        // Log contents may contain many complete labels, but remain raw when exported.
        if ('\n' in value) return value.split('\n').joinToString("\n") { translate(it, depth + 1) }
        for (template in templates) {
            val match = template.pattern.matchEntire(value) ?: continue
            val arguments = template.arguments.mapIndexed { index, argument ->
                argument to translate(match.groupValues[index + 1], depth + 1)
            }.toMap()
            return placeholder.replace(template.english) { arguments[it.groupValues[1].toInt()] ?: it.value }
        }
        // Composed lists of known labels (e.g. Health type names).
        for (separator in listOf(", ", " · ")) {
            if (separator in value) return value.split(separator).joinToString(separator) { translate(it, depth + 1) }
        }
        return value
    }
}
