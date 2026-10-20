package com.meetingatlas.recording

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Uses supported communication routing on Android 12+. Earlier versions retain system routing. */
class RecordingInputManager(private val context: Context, private val onChange: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** Captured when the route actually changes, not when the manager is constructed. */
    private var oldMode: Int? = null
    private var changedRoute = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { onChange() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { onChange() }
    }
    fun start(preferBluetooth: Boolean): Boolean {
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        if (!preferBluetooth || Build.VERSION.SDK_INT < 31) return false
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val target = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: return false
        val previous = audio.mode
        return try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            changedRoute = audio.setCommunicationDevice(target)
            if (changedRoute) {
                oldMode = previous
                true
            } else {
                // Restore the mode immediately; nothing was routed.
                audio.mode = previous
                false
            }
        } catch (_: Exception) {
            // A revoked permission or an OEM failure must not leak MODE_IN_COMMUNICATION.
            runCatching { audio.mode = previous }
            changedRoute = false
            oldMode = null
            false
        }
    }
    fun close() {
        runCatching { audio.unregisterAudioDeviceCallback(callback) }
        if (changedRoute && Build.VERSION.SDK_INT >= 31) {
            runCatching { audio.clearCommunicationDevice() }
            oldMode?.let { mode -> runCatching { audio.mode = mode } }
        }
        oldMode = null
        changedRoute = false
    }
    companion object {
        fun label(type: Int?) = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
            else -> "System microphone"
        }
    }
}
