package com.wdtt.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionStage(val displayName: String) {
    DNS("DNS"),
    VK("VK"),
    WRAP("WRAP"),
    TURN("TURN"),
    DTLS("DTLS"),
    STREAMS("Потоки"),
    VPN("TUN")
}

enum class StageStatus {
    WAITING,
    RUNNING,
    SUCCESS,
    ERROR
}

enum class ConnectionLifecycle {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
    DISCONNECTING
}

data class ConnectionState(
    val currentStage: ConnectionStage?,
    val stageStatuses: Map<ConnectionStage, StageStatus>,
    val statusText: String,
    val timeoutSeconds: Int?,
    val errorReason: String?,
    val lifecycle: ConnectionLifecycle
)

object ConnectionProgressManager {
    private const val DEFAULT_TIMEOUT_SECONDS = 30

    private val stageOrder = ConnectionStage.values().toList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disconnectJob: Job? = null

    private fun waitingStages(): LinkedHashMap<ConnectionStage, StageStatus> =
        LinkedHashMap<ConnectionStage, StageStatus>().apply {
            stageOrder.forEach { put(it, StageStatus.WAITING) }
        }

    private fun idleState() = ConnectionState(
        currentStage = null,
        stageStatuses = waitingStages(),
        statusText = "Готово к подключению",
        timeoutSeconds = null,
        errorReason = null,
        lifecycle = ConnectionLifecycle.IDLE
    )

    val state = MutableStateFlow(idleState())

    private fun runningText(stage: ConnectionStage, timeoutSeconds: Int): String =
        "Таймаут $timeoutSeconds с: ${stage.displayName}"

    fun beginConnection(timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS) {
        disconnectJob?.cancel()
        val stages = waitingStages().apply {
            put(ConnectionStage.DNS, StageStatus.RUNNING)
        }
        state.value = ConnectionState(
            currentStage = ConnectionStage.DNS,
            stageStatuses = stages,
            statusText = runningText(ConnectionStage.DNS, timeoutSeconds),
            timeoutSeconds = timeoutSeconds,
            errorReason = null,
            lifecycle = ConnectionLifecycle.CONNECTING
        )
    }

    fun runStage(
        stage: ConnectionStage,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        statusText: String = runningText(stage, timeoutSeconds)
    ) {
        disconnectJob?.cancel()
        state.update { current ->
            val stages = LinkedHashMap(current.stageStatuses)
            stages[stage] = StageStatus.RUNNING
            current.copy(
                currentStage = stage,
                stageStatuses = stages,
                statusText = statusText,
                timeoutSeconds = timeoutSeconds,
                errorReason = null,
                lifecycle = ConnectionLifecycle.CONNECTING
            )
        }
    }

    fun completeStage(stage: ConnectionStage, statusText: String? = null) {
        disconnectJob?.cancel()
        state.update { current ->
            val stages = LinkedHashMap(current.stageStatuses)
            stages[stage] = StageStatus.SUCCESS
            current.copy(
                stageStatuses = stages,
                statusText = statusText ?: current.statusText,
                errorReason = null,
                lifecycle = if (current.lifecycle == ConnectionLifecycle.CONNECTED) {
                    ConnectionLifecycle.CONNECTED
                } else {
                    ConnectionLifecycle.CONNECTING
                }
            )
        }
    }

    fun completeStageAndRun(
        completedStage: ConnectionStage,
        nextStage: ConnectionStage,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
    ) {
        disconnectJob?.cancel()
        state.update { current ->
            val stages = LinkedHashMap(current.stageStatuses)
            stages[completedStage] = StageStatus.SUCCESS
            stages[nextStage] = StageStatus.RUNNING
            current.copy(
                currentStage = nextStage,
                stageStatuses = stages,
                statusText = runningText(nextStage, timeoutSeconds),
                timeoutSeconds = timeoutSeconds,
                errorReason = null,
                lifecycle = ConnectionLifecycle.CONNECTING
            )
        }
    }

    fun fail(stage: ConnectionStage, reason: String = stage.displayName) {
        disconnectJob?.cancel()
        state.update { current ->
            val stages = LinkedHashMap(current.stageStatuses)
            stages[stage] = StageStatus.ERROR
            current.copy(
                currentStage = stage,
                stageStatuses = stages,
                statusText = "Ошибка: $reason",
                timeoutSeconds = null,
                errorReason = reason,
                lifecycle = ConnectionLifecycle.ERROR
            )
        }
    }

    fun failCurrent(reason: String) {
        val currentStage = state.value.currentStage ?: ConnectionStage.DNS
        fail(currentStage, reason)
    }

    fun markConnected() {
        disconnectJob?.cancel()
        val stages = waitingStages().apply {
            stageOrder.forEach { put(it, StageStatus.SUCCESS) }
        }
        state.value = ConnectionState(
            currentStage = ConnectionStage.VPN,
            stageStatuses = stages,
            statusText = "Подключено",
            timeoutSeconds = null,
            errorReason = null,
            lifecycle = ConnectionLifecycle.CONNECTED
        )
    }

    fun markDisconnecting() {
        val current = state.value
        if (current.lifecycle == ConnectionLifecycle.IDLE ||
            current.lifecycle == ConnectionLifecycle.DISCONNECTING
        ) {
            return
        }

        disconnectJob?.cancel()
        disconnectJob = scope.launch {
            state.update {
                it.copy(
                    statusText = "Отключение...",
                    timeoutSeconds = null,
                    errorReason = null,
                    lifecycle = ConnectionLifecycle.DISCONNECTING
                )
            }

            val currentStages = LinkedHashMap(state.value.stageStatuses)
            for (stage in stageOrder.asReversed()) {
                if (currentStages[stage] != StageStatus.WAITING) {
                    currentStages[stage] = StageStatus.WAITING
                    state.update {
                        it.copy(
                            currentStage = stage,
                            stageStatuses = LinkedHashMap(currentStages)
                        )
                    }
                    delay(70)
                }
            }

            state.value = idleState()
        }
    }

    fun resetToWaiting() {
        disconnectJob?.cancel()
        state.value = idleState()
    }
}
