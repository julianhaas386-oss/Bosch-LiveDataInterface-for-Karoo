package de.dxmedia.bosch.ldi.data

import android.content.SharedPreferences
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests the resilient SharedPreferences resolution used by [BikeRepository].
 *
 * The real failure mode (issue #4: grey start screen) is `EncryptedSharedPreferences.create`
 * throwing on devices with a non-standard keystore (e.g. Karoo). These tests pin down the
 * recovery logic without touching the Android keystore.
 */
class BikeRepositoryPrefsTest {

    private val encrypted = mockk<SharedPreferences>()
    private val fallback = mockk<SharedPreferences>()

    @Test fun `returns encrypted prefs when first attempt succeeds and does not clear`() {
        var cleared = false
        var openCount = 0

        val result = BikeRepository.resolvePrefs(
            openEncrypted = { openCount++; encrypted },
            clearCorrupt = { cleared = true },
            openFallback = { fallback }
        )

        assertSame(encrypted, result)
        assertEquals(1, openCount)
        assertTrue(!cleared, "must not clear keyset when first attempt succeeds")
    }

    @Test fun `clears corrupt keyset and retries when first attempt throws`() {
        var cleared = false
        var openCount = 0

        val result = BikeRepository.resolvePrefs(
            openEncrypted = {
                openCount++
                if (openCount == 1) throw SecurityException("bad keyset") else encrypted
            },
            clearCorrupt = { cleared = true },
            openFallback = { fallback }
        )

        assertSame(encrypted, result)
        assertEquals(2, openCount)
        assertTrue(cleared, "must clear corrupt keyset before retrying")
    }

    @Test fun `falls back to plain prefs when encrypted prefs are unavailable`() {
        var cleared = false
        var openCount = 0

        val result = BikeRepository.resolvePrefs(
            openEncrypted = { openCount++; throw SecurityException("keystore broken") },
            clearCorrupt = { cleared = true },
            openFallback = { fallback }
        )

        assertSame(fallback, result)
        assertEquals(2, openCount)
        assertTrue(cleared, "must attempt recovery before falling back")
    }
}
