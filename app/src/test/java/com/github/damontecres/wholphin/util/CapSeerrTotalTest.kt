package com.github.damontecres.wholphin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapSeerrTotalTest {
    @Test
    fun `TMDB maximum is capped to the reachable pages`() {
        assertEquals(10_000, capSeerrTotal(20_001, enabled = true))
    }

    @Test
    fun `a list just past the reachable pages is capped`() {
        assertEquals(10_000, capSeerrTotal(10_415, enabled = true))
    }

    @Test
    fun `a list inside the reachable pages keeps its count`() {
        assertEquals(969, capSeerrTotal(969, enabled = true))
    }

    @Test
    fun `an unknown total stays unknown`() {
        assertNull(capSeerrTotal(null, enabled = true))
    }

    @Test
    fun `upstream flavors keep the reported total`() {
        assertEquals(20_001, capSeerrTotal(20_001, enabled = false))
    }
}
