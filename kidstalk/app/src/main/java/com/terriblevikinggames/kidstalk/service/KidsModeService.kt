package com.terriblevikinggames.kidstalk.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.terriblevikinggames.kidstalk.MainActivity
import com.terriblevikinggames.kidstalk.audio.AudioEngine
import com.terriblevikinggames.kidstalk.headset.HeadsetButtonController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps Kids Mode alive with the screen off: owns the [AudioEngine] session
 * and the [HeadsetButtonController] for as long as it is in the foreground.
 */
class KidsModeService : Service() {

    inner class LocalBinder : Binder() {
        val engineState: StateFlow<AudioEngine.State> get() = AudioEngine.state
        val routing: StateFlow<AudioEngine.Routing?> get() = AudioEngine.routing
        val isKidsMicMuted: StateFlow<Boolean> get() = AudioEngine.isKidsMicMuted
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var buttonController: HeadsetButtonController
    private var running = false

    override fun onCreate() {
        super.onCreate()
        buttonController = HeadsetButtonController(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> startKidsMode()
        }
        // A microphone foreground service may not be restarted from the
        // background, so do not ask the system to revive it.
        return START_NOT_STICKY
    }

    private fun startKidsMode() {
        if (running) return

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted || !KidsModePermissions.allGranted(this)) {
            Log.e(TAG, "Missing permissions: ${KidsModePermissions.missing(this)}")
            stopSelf()
            return
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: Exception) {
            // Thrown when started while the app is not visible (Android 12+).
            Log.e(TAG, "Could not enter the foreground", e)
            stopSelf()
            return
        }

        running = true
        buttonController.start()
        AudioEngine.startKidsMode(this)

        // If the bridge dies (headset disconnected, no headset at all), shut
        // down instead of holding the mic and a notification for nothing. The
        // Error state stays in AudioEngine.state for the UI to show.
        scope.launch {
            AudioEngine.state.collect { state ->
                if (state is AudioEngine.State.Error) stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (running) {
            AudioEngine.stop()
            buttonController.stop()
            running = false
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Kids Mode", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while the walkie-talkie is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kids Mode is on")
            .setContentText("Phone and headset are connected as a walkie-talkie")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "KidsModeService"
        private const val CHANNEL_ID = "kids_mode"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.terriblevikinggames.kidstalk.action.STOP"

        /** Call only while the app is visible and permissions are granted. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KidsModeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KidsModeService::class.java))
        }

        private fun stopIntent(context: Context) =
            Intent(context, KidsModeService::class.java).setAction(ACTION_STOP)
    }
}
