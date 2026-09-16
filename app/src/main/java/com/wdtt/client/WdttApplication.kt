package com.wdtt.client

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
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
		Log.i("PushRegistration", "APP_START")
		val firebaseApps = FirebaseApp.getApps(this)
		Log.i("PushRegistration", "FIREBASE_APPS_COUNT=${firebaseApps.size}")
		val firebaseInitialized = runCatching { FirebaseApp.getInstance(); true }
			.getOrElse {
				Log.e("PushRegistration", "FIREBASE_ERROR=${it::class.java.simpleName}")
				false
			}
		Log.i("PushRegistration", "FIREBASE_INITIALIZED=$firebaseInitialized")
		NotificationHelper.ensureTunnelChannel(this)
		NotificationHelper.ensurePushChannels(this)
		ServerNotificationStore.get(this)
        DeployManager.init(this)
        AppShortcuts.refreshAsync(this)
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			reconcileAppUpdateState(this@WdttApplication)
		}
		PushRegistrationClient.registerCurrentToken(this)
	}

    fun getBackend(context: Context): GoBackend {
        return backendInstance ?: synchronized(this) {
            backendInstance ?: GoBackend(context.applicationContext).also { backendInstance = it }
        }
    }
}
