package com.wdtt.client

import android.os.Build
import java.util.Locale

object DeviceInfo {

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }

        val model = Build.MODEL.trim()

        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
}