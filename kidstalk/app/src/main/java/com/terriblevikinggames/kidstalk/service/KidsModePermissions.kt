package com.terriblevikinggames.kidstalk.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions for Kids Mode. MODIFY_AUDIO_SETTINGS is install-time only. */
object KidsModePermissions {

    /** Kids Mode cannot start without these. */
    val required: List<String> = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** Everything worth asking for; the notification permission is optional. */
    val toRequest: List<String> = buildList {
        addAll(required)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missing(context: Context): List<String> = required.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()
}
