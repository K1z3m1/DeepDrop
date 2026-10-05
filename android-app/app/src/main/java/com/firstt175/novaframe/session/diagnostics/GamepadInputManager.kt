package com.firstt175.novaframe.session.diagnostics

import com.firstt175.novaframe.session.NovaLog

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether a physical game controller (gamepad/joystick) is currently
 * connected, application-wide. Initialised once from
 * [com.firstt175.novaframe.NovaApplication] so both the Compose settings UI and
 * the in-game overlays can react to a controller being plugged in or removed
 * without each owning a separate [InputManager] listener.
 *
 * "Gamepad" means any [InputDevice] exposing [InputDevice.SOURCE_GAMEPAD] or
 * [InputDevice.SOURCE_JOYSTICK] that isn't a virtual/synthetic device — this
 * deliberately excludes things like the on-screen d-pad some TV boxes
 * synthesize, which sets SOURCE_DPAD but not SOURCE_GAMEPAD/SOURCE_JOYSTICK.
 */
object GamepadInputManager {

    private const val TAG = "GamepadInputManager"

    data class ConnectedGamepad(val deviceId: Int, val name: String)

    private val _connected = MutableStateFlow<ConnectedGamepad?>(null)

    /** Null when no controller is connected; otherwise the most recently attached one. */
    val connected: StateFlow<ConnectedGamepad?> = _connected.asStateFlow()

    private var initialized = false
    private var inputManager: InputManager? = null

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = inputManager?.getInputDevice(deviceId) ?: return
            if (isGamepad(device)) {
                NovaLog.i(TAG, "Gamepad connected: ${device.name} (id=$deviceId)")
                _connected.value = ConnectedGamepad(deviceId, device.name)
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (_connected.value?.deviceId == deviceId) {
                NovaLog.i(TAG, "Gamepad disconnected (id=$deviceId)")
                // Fall back to another still-connected controller, if any,
                // instead of going blank when the user has more than one paired.
                _connected.value = findFirstConnectedGamepad()
            }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            // No-op — source/name don't change on an already-connected device.
        }
    }

    /** Idempotent — safe to call from Application.onCreate() every process start. */
    fun init(ctx: Context) {
        if (initialized) return
        val im = ctx.applicationContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
            ?: return
        initialized = true
        inputManager = im
        // handler=null → callbacks land on this (the caller's) thread's looper,
        // which is the main thread since init() is only ever called from
        // NovaApplication.onCreate().
        im.registerInputDeviceListener(listener, null)
        _connected.value = findFirstConnectedGamepad()
    }

    private fun findFirstConnectedGamepad(): ConnectedGamepad? {
        val im = inputManager ?: return null
        for (id in im.inputDeviceIds) {
            val device = im.getInputDevice(id) ?: continue
            if (isGamepad(device)) return ConnectedGamepad(id, device.name)
        }
        return null
    }

    private fun isGamepad(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        val sources = device.sources
        val isGamepadSource = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val isJoystickSource = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return isGamepadSource || isJoystickSource
    }
}
