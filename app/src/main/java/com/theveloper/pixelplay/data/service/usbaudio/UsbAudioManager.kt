package com.theveloper.pixelplay.data.service.usbaudio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages USB audio device detection and routing for PixelPlayer.
 *
 * Detects when a USB DAC/amp is connected via USB-C and exposes a [StateFlow]
 * of the currently connected USB audio output device. When a USB DAC is set as
 * the preferred device and USB DAC Mode is enabled, this class routes ExoPlayer's
 * audio output exclusively to the USB DAC, bypassing the phone's internal DAC/amp
 * and any connected Bluetooth device.
 *
 * Behaviour:
 * - Connecting a USB DAC does NOT automatically enable USB DAC Mode (user must toggle it).
 * - Disconnecting a USB DAC auto-pauses playback and disables USB DAC Mode.
 * - USB DAC takes priority over Bluetooth when USB DAC Mode is enabled.
 */
@Singleton
class UsbAudioManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Emits the currently connected USB audio output device, or null if none.
    private val _connectedUsbDac = MutableStateFlow<AudioDeviceInfo?>(null)
    val connectedUsbDac: StateFlow<AudioDeviceInfo?> = _connectedUsbDac.asStateFlow()

    // Internal callback: reacts to real-time USB device connect/disconnect events.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val usbDac = addedDevices.firstOrNull { it.isUsbAudioOutput() }
            if (usbDac != null) {
                Timber.tag(TAG).d("USB DAC connected: ${usbDac.productName} (id=${usbDac.id})")
                _connectedUsbDac.value = usbDac
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val currentDac = _connectedUsbDac.value ?: return
            val removed = removedDevices.any { it.id == currentDac.id }
            if (removed) {
                Timber.tag(TAG).d("USB DAC disconnected: ${currentDac.productName}")
                _connectedUsbDac.value = null
            }
        }
    }

    init {
        // Register on main thread handler — AudioDeviceCallback requires a Looper.
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        // Perform an initial scan so that if a DAC was already connected we pick it up.
        refreshUsbDevices()
    }

    /**
     * Scans currently connected output devices and updates [connectedUsbDac].
     * Call this when you need a fresh snapshot (e.g. on service start).
     */
    fun refreshUsbDevices() {
        val usbDac = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isUsbAudioOutput() }
        if (usbDac != null) {
            Timber.tag(TAG).d("Initial USB DAC scan: found ${usbDac.productName}")
        }
        _connectedUsbDac.value = usbDac
    }



    /**
     * Releases the [AudioDeviceCallback] registration. Call this when the
     * owning service is destroyed to avoid resource leaks.
     */
    fun release() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error unregistering AudioDeviceCallback")
        }
    }

    /**
     * Returns a human-readable display name for the connected USB DAC.
     * Returns null if no USB DAC is currently connected.
     */
    fun getUsbDacDisplayName(): String? {
        return _connectedUsbDac.value?.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "UsbAudioManager"

        /**
         * Returns true if this [AudioDeviceInfo] is a USB audio output device
         * (DAC, headset, or accessory with audio capability).
         */
        fun AudioDeviceInfo.isUsbAudioOutput(): Boolean {
            if (!isSink) return false
            return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                   type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                   type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
    }
}
