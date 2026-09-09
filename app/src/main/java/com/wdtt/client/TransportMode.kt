package com.wdtt.client

enum class TransportMode {
    AUTO,
    NORMAL,
    DIRECT,
    TURN_TCP,
    RAW_TUN,
}

fun normalizeTransportMode(raw: String?): TransportMode {
    return when (raw?.trim()?.lowercase()) {
        "auto" -> TransportMode.AUTO
        "direct" -> TransportMode.DIRECT
        "turn_tcp", "turn-tcp", "turntcp" -> TransportMode.TURN_TCP
        "raw_tun", "raw-tun", "rawtun" -> TransportMode.RAW_TUN
        "normal", "dtls", "default" -> TransportMode.NORMAL
        else -> TransportMode.NORMAL
    }
}

fun transportModeFromLegacy(directEnabled: Boolean, turnTcpEnabled: Boolean): TransportMode {
    return when {
        directEnabled -> TransportMode.DIRECT
        turnTcpEnabled -> TransportMode.TURN_TCP
        else -> TransportMode.NORMAL
    }
}

fun TransportMode.toPersistedValue(): String {
    return when (this) {
        TransportMode.AUTO -> "auto"
        TransportMode.NORMAL -> "normal"
        TransportMode.DIRECT -> "direct"
        TransportMode.TURN_TCP -> "turn_tcp"
        TransportMode.RAW_TUN -> "raw_tun"
    }
}

fun TransportMode.resolveForRuntime(): TransportMode {
    // В эталонной реализации отсутствие явного флага означает обычный DTLS.
    return if (this == TransportMode.AUTO) TransportMode.NORMAL else this
}

fun TransportMode.isDirect(): Boolean = this == TransportMode.DIRECT

fun TransportMode.isTurnTcp(): Boolean = this == TransportMode.TURN_TCP

fun TransportMode.isRawTun(): Boolean = this == TransportMode.RAW_TUN

fun transportModeTitle(mode: TransportMode): String {
    return when (mode) {
        TransportMode.AUTO -> "Автоматически"
        TransportMode.NORMAL -> "Normal"
        TransportMode.DIRECT -> "Direct"
        TransportMode.TURN_TCP -> "TURN TCP"
        TransportMode.RAW_TUN -> "RAW TUN"
    }
}

fun transportModeDescription(mode: TransportMode): String {
    return when (mode) {
        TransportMode.AUTO -> "Выбирать доступный транспорт автоматически."
        TransportMode.NORMAL -> "Обычное подключение через DTLS"
        TransportMode.DIRECT -> "Прямое подключение без DTLS"
        TransportMode.TURN_TCP -> "Подключение через TURN TCP"
        TransportMode.RAW_TUN -> "Передача IP-трафика через RAW TUN"
    }
}
