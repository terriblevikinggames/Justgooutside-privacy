package com.terriblevikinggames.kidstalk.audio

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Headless split-routing audio engine for "Kids Mode".
 *
 *   Phone mic  --PCM-->  Bluetooth speaker   (parent talks to kids)
 *   BT mic     --PCM-->  Phone speaker       (kids talk to parent)
 *
 * All four nodes are pinned to their hardware with setPreferredDevice(); the
 * two directions are pumped as raw 16-bit / 16 kHz / mono PCM on Dispatchers.IO.
 */
object AudioEngine {

    private const val TAG = "AudioEngine"

    const val SAMPLE_RATE = 16_000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 20 ms of 16-bit mono audio: the unit moved per read/write. */
    private const val CHUNK_BYTES = SAMPLE_RATE / 50 * 2

    private const val SCO_CONNECT_TIMEOUT_MS = 6_000L

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data object Running : State
        data class Error(val message: String) : State
    }

    /** Product names of the hardware each node is actually routed to. */
    data class Routing(
        val phoneMic: String,
        val phoneSpeaker: String,
        val bluetoothMic: String,
        val bluetoothSpeaker: String,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _routing = MutableStateFlow<Routing?>(null)
    val routing: StateFlow<Routing?> = _routing.asStateFlow()

    /**
     * When true the kids' mic is still drained (so the SCO link and AEC stay
     * warm) but nothing is written to the phone speaker. Phase 2's headset
     * button toggles this.
     */
    val isKidsMicMuted = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises sessions so a restart waits for the previous teardown. */
    private val sessionMutex = Mutex()
    private val lock = Any()
    private var sessionJob: Job? = null

    /**
     * Starts the two-way bridge. Returns immediately; observe [state] for
     * Starting -> Running (or Error, e.g. no headset connected).
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startKidsMode(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (sessionJob?.isActive == true) return
            sessionJob = scope.launch {
                sessionMutex.withLock { runSession(appContext) }
            }
        }
    }

    /** Stops the bridge and restores the previous audio mode/routing. */
    fun stop() {
        synchronized(lock) {
            sessionJob?.cancel()
            sessionJob = null
        }
    }

    // ---------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun runSession(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val previousMode = am.mode
        val nodes = Nodes()
        var failure: String? = null

        try {
            _state.value = State.Starting
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // The headset mic only delivers audio once the SCO (or LE Audio)
            // voice link is up, so bring that up before touching AudioRecord.
            val commDevice = connectBluetoothVoiceLink(am)

            val builtinMic = am.findInput(AudioDeviceInfo.TYPE_BUILTIN_MIC)
                ?: throw IOException("No built-in microphone found")
            val builtinSpeaker = am.findOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                ?: throw IOException("No built-in speaker found")
            val btMic = am.findInput(commDevice.type)
                ?: am.findInput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
                ?: throw IOException("Bluetooth headset has no microphone input")
            // SCO first: A2DP is suspended while the SCO voice link is open,
            // so it is only a last resort.
            val btSpeaker = am.findOutput(
                commDevice.type,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ) ?: throw IOException("Bluetooth headset has no speaker output")

            // --- My interface (phone) ---
            nodes.phoneMic = buildRecord(MediaRecorder.AudioSource.MIC, builtinMic)
            // USAGE_MEDIA keeps this track off the communication device (the
            // headset) so the explicit speaker route is honoured.
            nodes.phoneSpeaker = buildTrack(AudioAttributes.USAGE_MEDIA, builtinSpeaker)

            // --- Kids' interface (Bluetooth) ---
            nodes.btMic = buildRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, btMic)
            nodes.btSpeaker = buildTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, btSpeaker)
            nodes.attachEffects()

            val phoneMic = nodes.phoneMic!!
            val phoneSpeaker = nodes.phoneSpeaker!!
            val kidsMic = nodes.btMic!!
            val kidsSpeaker = nodes.btSpeaker!!

            phoneSpeaker.play()
            kidsSpeaker.play()
            phoneMic.startRecordingOrThrow("phone mic")
            kidsMic.startRecordingOrThrow("Bluetooth mic")

            _routing.value = Routing(
                phoneMic = phoneMic.routedDevice.nameOr(builtinMic),
                phoneSpeaker = phoneSpeaker.routedDevice.nameOr(builtinSpeaker),
                bluetoothMic = kidsMic.routedDevice.nameOr(btMic),
                bluetoothSpeaker = kidsSpeaker.routedDevice.nameOr(btSpeaker),
            )
            warnIfMisrouted("phone mic", builtinMic, phoneMic.routedDevice)
            warnIfMisrouted("phone speaker", builtinSpeaker, phoneSpeaker.routedDevice)
            warnIfMisrouted("BT mic", btMic, kidsMic.routedDevice)
            warnIfMisrouted("BT speaker", btSpeaker, kidsSpeaker.routedDevice)

            _state.value = State.Running
            Log.i(TAG, "Kids mode running: ${_routing.value}")

            coroutineScope {
                launch { pump("phone->kids", phoneMic, kidsSpeaker) }
                launch { pump("kids->phone", kidsMic, phoneSpeaker) { isKidsMicMuted.value } }
                // AudioRecord.read() blocks and cannot see cancellation, so on
                // cancel stop the records to kick both pumps out of read().
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { phoneMic.stop() }
                        runCatching { kidsMic.stop() }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Kids mode failed", e)
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                nodes.release()
                am.clearCommunicationDevice()
                am.mode = previousMode
                _routing.value = null
                _state.value = failure?.let { State.Error(it) } ?: State.Idle
            }
        }
    }

    /**
     * Makes the Bluetooth headset the communication device and waits for the
     * voice link to actually come up.
     */
    private suspend fun connectBluetoothVoiceLink(am: AudioManager): AudioDeviceInfo {
        val headset = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: throw IOException("No Bluetooth headset connected")

        if (!am.setCommunicationDevice(headset)) {
            throw IOException("System refused to open the Bluetooth voice link")
        }
        withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) {
            while (am.communicationDevice?.id != headset.id) delay(100)
        } ?: throw IOException("Bluetooth voice link did not come up in time")
        return headset
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildRecord(source: Int, device: AudioDeviceInfo): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_IN)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord init failed for ${device.productName}")
        }
        if (!record.setPreferredDevice(device)) {
            Log.w(TAG, "setPreferredDevice rejected for input ${device.productName}")
        }
        return record
    }

    private fun buildTrack(usage: Int, device: AudioDeviceInfo): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IOException("AudioTrack init failed for ${device.productName}")
        }
        if (!track.setPreferredDevice(device)) {
            Log.w(TAG, "setPreferredDevice rejected for output ${device.productName}")
        }
        return track
    }

    /** Blocking record -> track copy loop; runs until cancelled or a node dies. */
    private suspend fun pump(
        name: String,
        record: AudioRecord,
        track: AudioTrack,
        muted: () -> Boolean = { false },
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = record.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read < 0) throw IOException("$name: AudioRecord.read failed ($read)")
            if (read == 0 || muted()) continue
            val written = track.write(buffer, 0, read)
            if (written < 0) throw IOException("$name: AudioTrack.write failed ($written)")
        }
    }

    private fun AudioRecord.startRecordingOrThrow(name: String) {
        startRecording()
        if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IOException("Could not start recording from $name")
        }
    }

    private fun AudioManager.findInput(vararg types: Int): AudioDeviceInfo? {
        val devices = getDevices(AudioManager.GET_DEVICES_INPUTS)
        return firstByTypePriority(devices, types)
    }

    private fun AudioManager.findOutput(vararg types: Int): AudioDeviceInfo? {
        val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return firstByTypePriority(devices, types)
    }

    /** First device matching the earliest type in [types] (priority order). */
    private fun firstByTypePriority(devices: Array<AudioDeviceInfo>, types: IntArray): AudioDeviceInfo? {
        for (type in types) devices.firstOrNull { it.type == type }?.let { return it }
        return null
    }

    private fun AudioDeviceInfo?.nameOr(fallback: AudioDeviceInfo): String =
        (this ?: fallback).productName.toString()

    private fun warnIfMisrouted(node: String, wanted: AudioDeviceInfo, actual: AudioDeviceInfo?) {
        if (actual != null && actual.id != wanted.id) {
            Log.w(TAG, "$node wanted ${wanted.productName} (type ${wanted.type}) " +
                "but is routed to ${actual.productName} (type ${actual.type})")
        }
    }

    /** The four audio nodes plus the effects bound to the kids' mic. */
    private class Nodes {
        var phoneMic: AudioRecord? = null
        var phoneSpeaker: AudioTrack? = null
        var btMic: AudioRecord? = null
        var btSpeaker: AudioTrack? = null
        private var echoCanceler: AcousticEchoCanceler? = null
        private var noiseSuppressor: NoiseSuppressor? = null

        /** Open-ear headset: the kids' mic hears its own speaker, so AEC + NS. */
        fun attachEffects() {
            val session = btMic!!.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(session)?.also { it.enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(session)?.also { it.enabled = true }
            }
            if (echoCanceler == null) Log.w(TAG, "AcousticEchoCanceler unavailable on this device")
            if (noiseSuppressor == null) Log.w(TAG, "NoiseSuppressor unavailable on this device")
        }

        fun release() {
            runCatching { echoCanceler?.release() }
            runCatching { noiseSuppressor?.release() }
            for (record in listOf(phoneMic, btMic)) {
                runCatching { record?.stop() }
                runCatching { record?.release() }
            }
            for (track in listOf(phoneSpeaker, btSpeaker)) {
                runCatching { track?.pause(); track?.flush() }
                runCatching { track?.release() }
            }
        }
    }
}
