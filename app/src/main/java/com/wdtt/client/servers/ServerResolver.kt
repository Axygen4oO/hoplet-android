package com.wdtt.client.servers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.wdtt.client.SettingsStore

class ServerResolver(
    context: Context,
    settingsStore: com.wdtt.client.SettingsStore? = null,
) {
    private val resolvedSettingsStore = settingsStore ?: SettingsStore(context.applicationContext)
    private val serversStore = ServersStore(context.applicationContext, resolvedSettingsStore)

    fun getActiveServer(): Flow<ServerProfile?> = serversStore.getActiveServer()

    fun getActiveServerId(): Flow<String?> = serversStore.activeServerId

    suspend fun requireActiveServer(): ServerProfile {
        return getActiveServer().first()
            ?: throw IllegalStateException("Активный сервер не выбран")
    }

    suspend fun getActiveRuntimeConfig(): ServerRuntimeConfig? {
        val activeServer = serversStore.getActiveServerOrNull() ?: return null
        val connectionPassword = resolvedSettingsStore.connectionPassword.first()
        return buildActiveRuntimeConfig(activeServer, connectionPassword)
    }

    companion object {
        fun buildActiveRuntimeConfig(
            activeServer: ServerProfile?,
            connectionPassword: String?,
        ): ServerRuntimeConfig? {
            val server = activeServer?.normalized() ?: return null
            return ServerRuntimeConfig.from(server, connectionPassword)
        }
    }
}
