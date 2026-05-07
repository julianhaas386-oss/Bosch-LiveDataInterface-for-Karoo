package de.dxmedia.bosch.ldi.ui.bikes

import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BikeDetailViewModelTest {

    private fun makeProfile(slot: BikeSlot = BikeSlot.ALPHA, active: Boolean = false) =
        BikeProfile(slot = slot, bleAddress = "AA:BB:CC:DD:EE:FF", isActive = active)

    @Test
    fun `isActive reflects initial profile state`() {
        val vm = BikeDetailViewModel(makeProfile(active = true), {}, {}, {})
        assertTrue(vm.isActive.value)
    }

    @Test
    fun `toggleActive flips isActive`() {
        val vm = BikeDetailViewModel(makeProfile(active = false), {}, {}, {})
        vm.toggleActive()
        assertTrue(vm.isActive.value)
        vm.toggleActive()
        assertFalse(vm.isActive.value)
    }

    @Test
    fun `isFieldEnabled returns true for default fields`() {
        val vm = BikeDetailViewModel(makeProfile(), {}, {}, {})
        assertTrue(vm.isFieldEnabled("bosch_ldi_speed"))
    }

    @Test
    fun `toggleField disables an enabled field`() {
        val vm = BikeDetailViewModel(makeProfile(), {}, {}, {})
        vm.toggleField("bosch_ldi_speed")
        assertFalse(vm.isFieldEnabled("bosch_ldi_speed"))
    }

    @Test
    fun `toggleField re-enables a disabled field`() {
        val profile = makeProfile().let {
            it.copy(enabledFields = it.enabledFields - "bosch_ldi_speed")
        }
        val vm = BikeDetailViewModel(profile, {}, {}, {})
        vm.toggleField("bosch_ldi_speed")
        assertTrue(vm.isFieldEnabled("bosch_ldi_speed"))
    }

    @Test
    fun `save calls onSave with updated profile`() {
        var saved: BikeProfile? = null
        val vm = BikeDetailViewModel(makeProfile(), onSave = { saved = it }, {}, {})
        vm.toggleActive()
        vm.save()
        assertTrue(saved?.isActive == true)
    }

    @Test
    fun `forget calls onForget`() {
        var forgotSlot: BikeSlot? = null
        val vm = BikeDetailViewModel(makeProfile(BikeSlot.BETA), {}, onForget = { forgotSlot = it }, {})
        vm.forget()
        assertTrue(forgotSlot == BikeSlot.BETA)
    }
}
