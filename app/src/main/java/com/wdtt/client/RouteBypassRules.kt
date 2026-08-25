package com.wdtt.client

internal object RouteBypassRules {
    sealed interface AddResult {
        data class Added(
            val storedRoutes: String,
            val normalizedRule: String,
        ) : AddResult

        data class Invalid(val message: String) : AddResult

        data class Duplicate(val normalizedRule: String) : AddResult
    }

    fun parseStoredRules(raw: String): List<String> = BypassRoutes.parseRules(raw)

    fun isValidRule(rule: String): Boolean {
        val normalizedRule = BypassRoutes.normalizeRuleInput(rule)
        if (normalizedRule.isBlank()) {
            return false
        }
        return BypassRoutes.parseRuleToCidrs(normalizedRule) != null ||
            BypassRoutes.domainLookupHosts(normalizedRule) != null
    }

    fun addRule(existing: String, input: String): AddResult {
        val normalizedRule = BypassRoutes.normalizeRuleInput(input)
        if (normalizedRule.isBlank()) {
            return AddResult.Invalid("Некорректный адрес")
        }

        if (!isValidRule(normalizedRule)) {
            return AddResult.Invalid("Некорректный адрес")
        }

        val currentRules = parseStoredRules(existing)
        if (normalizedRule in currentRules) {
            return AddResult.Duplicate(normalizedRule)
        }

        val storedRoutes = BypassRoutes.normalizeRulesForStorage(
            (currentRules + normalizedRule).joinToString("\n"),
        )
        return AddResult.Added(
            storedRoutes = storedRoutes,
            normalizedRule = normalizedRule,
        )
    }

    fun deleteRule(existing: String, rule: String): String {
        val normalizedRule = BypassRoutes.normalizeRuleInput(rule)
        val updatedRules = parseStoredRules(existing).filterNot { it == normalizedRule }
        return BypassRoutes.normalizeRulesForStorage(updatedRules.joinToString("\n"))
    }

    fun shouldReloadAfterChange(isWhitelist: Boolean, isTunnelRunning: Boolean): Boolean {
        return !isWhitelist && isTunnelRunning
    }
}

internal interface RouteBypassRuleStorage {
    var routes: String
}

internal class RouteBypassRuleEditor(
    private val storage: RouteBypassRuleStorage,
) {
    fun loadRules(): List<String> = RouteBypassRules.parseStoredRules(storage.routes)

    fun addRule(input: String): RouteBypassRules.AddResult {
        val result = RouteBypassRules.addRule(storage.routes, input)
        if (result is RouteBypassRules.AddResult.Added) {
            storage.routes = result.storedRoutes
        }
        return result
    }

    fun deleteRule(rule: String): List<String> {
        storage.routes = RouteBypassRules.deleteRule(storage.routes, rule)
        return loadRules()
    }
}
