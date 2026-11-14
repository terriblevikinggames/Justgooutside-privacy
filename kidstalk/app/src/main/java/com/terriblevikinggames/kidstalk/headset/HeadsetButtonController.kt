package com.terriblevikinggames.kidstalk.headset

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.MainThread
import androidx.core.content.IntentCompat
import com.terriblevikinggames.kidstalk.audio.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Captures the Bluetooth headset's play/pause tap by posing as a music player.
 *
 * Android only delivers media buttons to the app it believes owns playback, so
 * this holds audio focus and keeps an active MediaSession permanently in
 * STATE_PLAYING. Each tap toggles [isKidsMicMuted].
 */
class HeadsetButtonController(
    context: Context,
    private val kidsMicMuted: MutableStateFlow<Boolean> = AudioEngine.isKidsMicMuted,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    val isKidsMicMuted: StateFlow<Boolean> = kidsMicMuted.asStateFlow()

    private var session: MediaSessionCompat? = null

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            // Another app took playback; headset taps go to it until we are
            // restarted. Nothing to pause here - the bridge keeps running.
            Log.w(TAG, "Audio focus changed: $change")
        }
        .build()

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(
                mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
            ) ?: return false
            if (event.keyCode !in TOGGLE_KEYS) return false

            // Act once per press: ignore the key-up and auto-repeats, but still
            // consume them so nothing else reacts to the tap.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                kidsMicMuted.update { !it }
                Log.i(
                    TAG,
                    "Headset button ${KeyEvent.keyCodeToString(event.keyCode)} intercepted; " +
                        "kids mic muted = ${kidsMicMuted.value}"
                )
            }
            return true
        }
    }

    /** Takes audio focus and activates the spoofed session. Returns false if focus was denied. */
    @MainThread
    fun start(): Boolean {
        if (session != null) return true

        val focus = audioManager.requestAudioFocus(focusRequest)
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.e(TAG, "Audio focus denied ($focus); headset button will not be captured")
            return false
        }

        session = MediaSessionCompat(appContext, TAG).apply {
            setCallback(callback)
            // Must look like it is playing, or the OS hands the taps to
            // whichever real player was last active.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            isActive = true
        }
        Log.i(TAG, "MediaSession active; listening for headset button")
        return true
    }

    @MainThread
    fun stop() {
        session?.run {
            isActive = false
            release()
        }
        session = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = "HeadsetButton"

        /**
         * PLAY and PAUSE are included because many earbuds send the discrete
         * AVRCP key matching the state they think the player is in, not
         * PLAY_PAUSE.
         */
        val TOGGLE_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
