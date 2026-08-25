package com.wdtt.client

internal object RouteBypassShare {
    const val DEFAULT_FILE_NAME = "wdtt-bypass-routes.txt"
    const val MAX_IMPORT_BYTES = 256 * 1024

    data class ImportResult(
        val validRules: List<String>,
        val addedCount: Int,
        val duplicateCount: Int,
        val invalidCount: Int,
    )

    fun exportRules(rules: List<String>): String = rules.joinToString("\n")

    fun parseImportedText(
        text: String,
        existingRules: List<String> = emptyList(),
    ): ImportResult {
        val mergedRules = LinkedHashSet<String>()
        existingRules.forEach { mergedRules += BypassRoutes.normalizeRuleInput(it) }

        var duplicateCount = 0
        var invalidCount = 0

        text
            .removePrefix("\uFEFF")
            .lineSequence()
            .forEach { rawLine ->
                val candidate = rawLine.substringBefore('#').trim()
                if (candidate.isEmpty()) {
                    return@forEach
                }

                val normalizedRule = BypassRoutes.normalizeRuleInput(candidate)
                if (!RouteBypassRules.isValidRule(normalizedRule)) {
                    invalidCount++
                    return@forEach
                }

                if (!mergedRules.add(normalizedRule)) {
                    duplicateCount++
                }
            }

        val normalizedExisting = existingRules.map(BypassRoutes::normalizeRuleInput)
        val addedCount = mergedRules.size - normalizedExisting.distinct().size

        return ImportResult(
            validRules = mergedRules.toList(),
            addedCount = addedCount.coerceAtLeast(0),
            duplicateCount = duplicateCount,
            invalidCount = invalidCount,
        )
    }
}
