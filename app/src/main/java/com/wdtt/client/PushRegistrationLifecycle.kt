package com.wdtt.client

/**
 * Small, Android-free state gate used to serialize registration attempts.
 * Persistent successful hashes remain in SharedPreferences; this gate prevents
 * duplicate concurrent HTTP requests and allows another attempt after failure.
 */
internal class PushRegistrationLifecycle {
    data class Key(val tokenHash: String, val subscriptionHash: String)

    sealed interface Decision {
        data object TokenMissing : Decision
        data object SubscriptionMissing : Decision
        data object AlreadyRegistered : Decision
        data object InFlight : Decision
        data class Start(val key: Key) : Decision
    }

    private val inFlight = mutableSetOf<Key>()

    @Synchronized
    fun begin(
        tokenHash: String?,
        subscriptionHash: String?,
        registeredTokenHash: String?,
        registeredSubscriptionHash: String?,
    ): Decision {
        if (tokenHash.isNullOrBlank()) return Decision.TokenMissing
        if (subscriptionHash.isNullOrBlank()) return Decision.SubscriptionMissing
        val key = Key(tokenHash, subscriptionHash)
        if (registeredTokenHash == tokenHash && registeredSubscriptionHash == subscriptionHash) {
            return Decision.AlreadyRegistered
        }
        if (!inFlight.add(key)) return Decision.InFlight
        return Decision.Start(key)
    }

    @Synchronized
    fun finish(key: Key) {
        inFlight.remove(key)
    }

    @Synchronized
    fun clear() {
        inFlight.clear()
    }
}
