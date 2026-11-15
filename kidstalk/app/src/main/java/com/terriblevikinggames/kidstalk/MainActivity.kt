package com.terriblevikinggames.kidstalk

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.terriblevikinggames.kidstalk.audio.AudioEngine
import com.terriblevikinggames.kidstalk.headset.HeadsetButtonController
import com.terriblevikinggames.kidstalk.service.KidsModePermissions
import com.terriblevikinggames.kidstalk.service.KidsModeService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bare-bones launcher for Phase 3: requests permissions and starts/stops the
 * service. Replaced by the Compose UI in Phase 4.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (KidsModePermissions.allGranted(this)) {
            KidsModeService.start(this)
        } else {
            status.text = "Microphone and Nearby devices permissions are needed.\n" +
                "Grant them in system settings if the prompt no longer appears."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 48) }
        val start = Button(this).apply {
            text = "Start Kids Mode"
            setOnClickListener { startKidsMode() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { KidsModeService.stop(this@MainActivity) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(status)
            addView(start)
            addView(stop)
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    AudioEngine.state,
                    AudioEngine.isKidsMicMuted,
                    AudioEngine.voiceLinkUp,
                    HeadsetButtonController.events,
                ) { state, muted, linkUp, buttons ->
                    val headline = when (state) {
                        AudioEngine.State.Idle -> "Stopped"
                        AudioEngine.State.Starting -> "Connecting to headset…"
                        AudioEngine.State.Running ->
                            if (muted) "Running — kids' mic muted" else "Running — kids' mic live"
                        is AudioEngine.State.Error -> "Error: ${state.message}"
                    }
                    val link = if (state == AudioEngine.State.Running) {
                        if (linkUp) "Headset voice link: up" else "Headset voice link: DROPPED"
                    } else ""
                    "$headline\n$link\nButton presses received: ${buttons.count}" +
                        " (last: ${buttons.lastKey ?: "none"})"
                }.collect { status.text = it }
            }
        }
    }

    private fun startKidsMode() {
        if (KidsModePermissions.allGranted(this)) {
            KidsModeService.start(this)
        } else {
            permissionRequest.launch(KidsModePermissions.toRequest.toTypedArray())
        }
    }
}
