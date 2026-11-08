package com.atmosphere.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test fun growsExponentiallyAndCaps() {
        assertEquals(5_000L, RelayEngine.backoffMs(1, null, 0.0))
        assertEquals(10_000L, RelayEngine.backoffMs(2, null, 0.0))
        assertEquals(300_000L, RelayEngine.backoffMs(20, null, 0.0))
    }

    @Test fun jitterStaysWithin20Percent() {
        assertEquals(6_000L, RelayEngine.backoffMs(1, null, 1.0))
    }

    @Test fun honorsRetryAfterWithinBounds() {
        assertEquals(30_000L, RelayEngine.backoffMs(1, 30L, 0.0))
        assertTrue(RelayEngine.backoffMs(1, 99_999L, 0.0) == 300_000L)
    }
}
