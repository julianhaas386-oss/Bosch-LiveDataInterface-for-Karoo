package de.dxmedia.bosch.ldi.data

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EncryptedSharedPreferences can throw SecurityException on READ when a stored
 * value fails to decrypt (corrupt entry / keyset mismatch that create() does not
 * detect). getProfiles() runs during first composition — an unguarded read would
 * kill the activity before the first frame (issue #4 follow-up hardening).
 */
class BikeRepositoryReadGuardTest {

    private fun throwingPrefs(): SharedPreferences = mockk {
        every { getString(any(), any()) } throws SecurityException("could not decrypt value")
        every { edit() } throws SecurityException("keystore unavailable")
    }

    @Test fun `getProfiles returns four default slots when the read throws`() {
        val repo = BikeRepository(throwingPrefs())

        val profiles = repo.getProfiles()

        assertEquals(BikeSlot.values().size, profiles.size)
        assertTrue(profiles.all { it.bleAddress == null && !it.isActive })
    }

    @Test fun `getActiveProfile returns null when the read throws`() {
        val repo = BikeRepository(throwingPrefs())

        assertNull(repo.getActiveProfile())
    }

    @Test fun `corrupt json still resets and returns defaults even if the reset write throws`() {
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } returns "{not-json"
            every { edit() } throws SecurityException("keystore unavailable")
        }
        val repo = BikeRepository(prefs)

        val profiles = repo.getProfiles()

        assertEquals(BikeSlot.values().size, profiles.size)
    }
}
