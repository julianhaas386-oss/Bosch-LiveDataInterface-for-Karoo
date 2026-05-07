package de.dxmedia.bosch.ldi.ui.bikes

import androidx.lifecycle.ViewModel
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BikeDetailViewModel(
    private val profile: BikeProfile,
    private val onSave: (BikeProfile) -> Unit,
    private val onForget: (BikeSlot) -> Unit,
    private val onSetActive: (BikeSlot) -> Unit
) : ViewModel() {

    private val _isActive = MutableStateFlow(profile.isActive)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _enabledFields = MutableStateFlow(profile.enabledFields)
    val enabledFields: StateFlow<Set<String>> = _enabledFields.asStateFlow()

    val slot: BikeSlot get() = profile.slot
    val bleAddress: String? get() = profile.bleAddress

    fun isFieldEnabled(fieldId: String): Boolean = _enabledFields.value.contains(fieldId)

    fun toggleActive() {
        _isActive.value = !_isActive.value
    }

    fun toggleField(fieldId: String) {
        val current = _enabledFields.value
        _enabledFields.value = if (fieldId in current) current - fieldId else current + fieldId
    }

    fun save() {
        val updated = profile.copy(
            isActive = _isActive.value,
            enabledFields = _enabledFields.value
        )
        if (_isActive.value && !profile.isActive) onSetActive(profile.slot)
        onSave(updated)
    }

    fun forget() {
        onForget(profile.slot)
    }
}
