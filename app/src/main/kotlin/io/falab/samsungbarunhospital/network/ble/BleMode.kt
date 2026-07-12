package io.falab.samsungbarunhospital.network.ble

import android.os.Build

/**
 * BLE peripheral (advertise + GATT server) mode is only reliable from API 26 onward. Below
 * that, a device can still participate as a central (scan + connect out), just never be
 * discovered/connected-to by others. See the plan's "known limitations" section:
 * CENTRAL_ONLY devices can never reach each other directly without a FULL device bridging them.
 */
enum class BleMode {
    /** minSdk floor not met, or required runtime permissions are missing. */
    UNSUPPORTED,
    /** API 24-25: can scan and connect out, but cannot advertise or run a GATT server. */
    CENTRAL_ONLY,
    /** API 26+: can both advertise/serve and scan/connect. */
    FULL
}

object BleModeResolver {
    private const val MIN_SDK = Build.VERSION_CODES.N // 24, matches app minSdk
    private const val FULL_SDK = Build.VERSION_CODES.O // 26

    /** Pure function (no Context/Build.VERSION.SDK_INT read) so it's unit-testable without Robolectric. */
    fun resolveMode(sdkInt: Int, hasRequiredPermissions: Boolean): BleMode {
        if (!hasRequiredPermissions || sdkInt < MIN_SDK) return BleMode.UNSUPPORTED
        return if (sdkInt >= FULL_SDK) BleMode.FULL else BleMode.CENTRAL_ONLY
    }
}
