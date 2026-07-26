package com.wdtt.client

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class VpnPermissionActivity : ComponentActivity() {

    private var startedFlow = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        continueAfterVpnResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        if (startedFlow) return
        startedFlow = true
        prepareVpnThenStart()
    }

    private fun prepareVpnThenStart() {
        if (TunnelManager.enabled.value) {
            finish()
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelAndFinish()
        }
    }

    private fun continueAfterVpnResult() {
        if (VpnService.prepare(this) == null) {
            startTunnelAndFinish()
        } else {
            TunnelManager.cancelConnectingIfNeeded()
            Toast.makeText(this, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startTunnelAndFinish() {
        TunnelControl.startFromSavedSettings(applicationContext)
        finish()
    }

    companion object {
        private const val EXTRA_SOURCE = "com.wdtt.client.extra.VPN_PERMISSION_SOURCE"

        const val SOURCE_TILE = "tile"
        const val SOURCE_WIDGET = "widget"

        fun createLaunchIntent(context: Context, source: String): Intent =
            Intent(context, VpnPermissionActivity::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun createPendingIntent(
            context: Context,
            source: String,
            requestCode: Int,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                createLaunchIntent(context, source),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
