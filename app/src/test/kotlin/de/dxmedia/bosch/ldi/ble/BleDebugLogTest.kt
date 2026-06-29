package de.dxmedia.bosch.ldi.ble

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleDebugLogTest {

    @After fun tearDown() {
        BleDebugLog.enabled = false
        BleDebugLog.clear()
    }

    @Test fun `does not capture while disabled`() {
        BleDebugLog.enabled = false
        BleDebugLog.clear()

        BleDebugLog.i("hello")
        BleDebugLog.e("boom")

        assertTrue(BleDebugLog.entries.value.isEmpty(), "disabled log must not capture")
    }

    @Test fun `captures while enabled and includes the message`() {
        BleDebugLog.enabled = true
        BleDebugLog.clear()

        BleDebugLog.i("advertising started")

        val lines = BleDebugLog.entries.value
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("advertising started"))
    }

    @Test fun `ring buffer is capped at MAX_ENTRIES dropping oldest`() {
        BleDebugLog.enabled = true
        BleDebugLog.clear()

        repeat(BleDebugLog.MAX_ENTRIES + 50) { BleDebugLog.i("line $it") }

        val lines = BleDebugLog.entries.value
        assertEquals(BleDebugLog.MAX_ENTRIES, lines.size)
        assertTrue(lines.first().contains("line 50"), "oldest entries must be dropped")
        assertTrue(lines.last().contains("line ${BleDebugLog.MAX_ENTRIES + 49}"))
    }

    @Test fun `clear empties the buffer`() {
        BleDebugLog.enabled = true
        BleDebugLog.i("something")

        BleDebugLog.clear()

        assertTrue(BleDebugLog.entries.value.isEmpty())
    }

    @Test fun `throwable message is captured`() {
        BleDebugLog.enabled = true
        BleDebugLog.clear()

        BleDebugLog.e("failed", IllegalStateException("bad keyset"))

        val text = BleDebugLog.entries.value.joinToString("\n")
        assertTrue(text.contains("failed"))
        assertTrue(text.contains("bad keyset"))
    }
}
