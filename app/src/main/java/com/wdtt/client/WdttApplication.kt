package com.wdtt.client

import android.app.Application
import android.content.Context
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WdttApplication : Application() {
    @Volatile
    private var backendInstance: GoBackend? = null

    val backend: GoBackend
        get() = getBackend(this)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureTunnelChannel(this)
        NotificationHelper.ensureServerNotificationsChannel(this)
        DeployManager.init(this)
        AppShortcuts.refreshAsync(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            reconcileAppUpdateState(this@WdttApplication)
        }
    }

    fun getBackend(context: Context): GoBackend {
        return backendInstance ?: synchronized(this) {
            backendInstance ?: GoBackend(context.applicationContext).also { backendInstance = it }
        }
    }
}
