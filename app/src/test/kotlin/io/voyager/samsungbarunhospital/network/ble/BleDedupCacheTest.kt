package io.voyager.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDedupCacheTest {

    @Test
    fun `first sighting is not a duplicate, second sighting is`() {
        val cache = BleDedupCache()
        assertFalse(cache.isDuplicate("abc123"))
        assertTrue(cache.isDuplicate("abc123"))
    }

    @Test
    fun `different ids are independent`() {
        val cache = BleDedupCache()
        assertFalse(cache.isDuplicate("id-a"))
        assertFalse(cache.isDuplicate("id-b"))
        assertTrue(cache.isDuplicate("id-a"))
        assertTrue(cache.isDuplicate("id-b"))
    }

    @Test
    fun `entry expires after the dedup window elapses`() {
        var now = 0L
        val cache = BleDedupCache(windowMs = 1000L, clock = { now })

        assertFalse(cache.isDuplicate("id"))
        now += 500
        assertTrue(cache.isDuplicate("id")) // still within window

        now += 600 // total elapsed 1100ms > 1000ms window
        assertFalse(cache.isDuplicate("id")) // expired, treated as fresh again
    }

    @Test
    fun `evicts oldest entry once capacity is exceeded`() {
        val cache = BleDedupCache(maxEntries = 2)
        assertFalse(cache.isDuplicate("first"))
        assertFalse(cache.isDuplicate("second"))
        // Re-checking existing entries is a cache hit and must not mutate insertion order.
        assertTrue(cache.isDuplicate("second"))
        assertTrue(cache.isDuplicate("first"))

        assertFalse(cache.isDuplicate("third")) // capacity exceeded -> evicts oldest ("first")
        assertEquals(2, cache.size())

        assertFalse(cache.isDuplicate("first")) // was evicted, treated as fresh again
        assertTrue(cache.isDuplicate("third"))  // untouched by the eviction above
    }
}
