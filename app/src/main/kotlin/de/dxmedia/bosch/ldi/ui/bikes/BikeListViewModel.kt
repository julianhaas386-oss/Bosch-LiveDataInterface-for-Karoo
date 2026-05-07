package de.dxmedia.bosch.ldi.ui.bikes

import androidx.lifecycle.ViewModel
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BikeListViewModel(private val repository: BikeRepository) : ViewModel() {

    private val _profiles = MutableStateFlow<List<BikeProfile>>(emptyList())
    val profiles: StateFlow<List<BikeProfile>> = _profiles.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _profiles.value = repository.getProfiles()
    }
}
