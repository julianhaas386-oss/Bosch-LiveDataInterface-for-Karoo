package de.dxmedia.bosch.ldi.ble

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertNotNull

class DependencySmoke {
    @Test
    fun `mockk and coroutines-test are on the classpath`() = runTest {
        val x = mockk<Any>(relaxed = true)
        assertNotNull(x)
    }
}
