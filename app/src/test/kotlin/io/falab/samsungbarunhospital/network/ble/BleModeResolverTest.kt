package io.falab.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleModeResolverTest {

    @Test
    fun `below minSdk is unsupported regardless of permissions`() {
        assertEquals(BleMode.UNSUPPORTED, BleModeResolver.resolveMode(sdkInt = 23, hasRequiredPermissions = true))
    }

    @Test
    fun `missing permissions is unsupported regardless of sdk`() {
        assertEquals(BleMode.UNSUPPORTED, BleModeResolver.resolveMode(sdkInt = 33, hasRequiredPermissions = false))
    }

    @Test
    fun `api 24-25 with permissions is central only`() {
        assertEquals(BleMode.CENTRAL_ONLY, BleModeResolver.resolveMode(sdkInt = 24, hasRequiredPermissions = true))
        assertEquals(BleMode.CENTRAL_ONLY, BleModeResolver.resolveMode(sdkInt = 25, hasRequiredPermissions = true))
    }

    @Test
    fun `api 26 and above with permissions is full`() {
        assertEquals(BleMode.FULL, BleModeResolver.resolveMode(sdkInt = 26, hasRequiredPermissions = true))
        assertEquals(BleMode.FULL, BleModeResolver.resolveMode(sdkInt = 33, hasRequiredPermissions = true))
    }
}
