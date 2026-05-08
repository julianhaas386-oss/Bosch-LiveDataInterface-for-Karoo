package de.dxmedia.bosch.ldi.ui.bikes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeSlot

@Composable
fun BikeListScreen(
    viewModel: BikeListViewModel,
    bleState: BleState,
    onPairSlot: (BikeSlot) -> Unit,
    onEditSlot: (BikeSlot) -> Unit,
    onNavigateSettings: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bike_list_title)) },
                actions = {
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            items(profiles, key = { it.slot.name }) { profile ->
                BikeSlotCard(
                    profile = profile,
                    bleState = bleState,
                    onClick = {
                        if (profile.bleAddress == null) onPairSlot(profile.slot)
                        else onEditSlot(profile.slot)
                    }
                )
            }
        }
    }
}

@Composable
private fun BikeSlotCard(profile: BikeProfile, bleState: BleState, onClick: () -> Unit) {
    val statusText = when {
        profile.bleAddress == null -> stringResource(R.string.bike_slot_not_paired)
        !profile.isActive -> stringResource(R.string.bike_slot_paired)
        bleState is BleState.Connected -> stringResource(R.string.bike_slot_ble_connected)
        bleState is BleState.Advertising -> stringResource(R.string.bike_slot_ble_connecting)
        else -> stringResource(R.string.bike_slot_ble_disconnected)
    }
    val statusColor = when {
        profile.isActive && bleState is BleState.Connected -> MaterialTheme.colorScheme.primary
        profile.isActive && bleState is BleState.Advertising -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.slot.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
            if (profile.isActive && bleState is BleState.Advertising) {
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            } else if (profile.isActive) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
