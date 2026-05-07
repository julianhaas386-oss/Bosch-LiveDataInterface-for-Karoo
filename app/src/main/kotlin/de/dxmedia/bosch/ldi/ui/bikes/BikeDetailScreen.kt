package de.dxmedia.bosch.ldi.ui.bikes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R

private val ALL_FIELDS = listOf(
    "bosch_ldi_speed",
    "bosch_ldi_cadence",
    "bosch_ldi_rider_power",
    "bosch_ldi_battery_soc",
    "bosch_ldi_odometer",
    "bosch_ldi_time",
    "bosch_ldi_bike_light",
    "bosch_ldi_ambient_brightness",
    "bosch_ldi_light_reserve_state",
    "bosch_ldi_system_locked",
    "bosch_ldi_charger_connected",
    "bosch_ldi_diagnosis_program_active",
    "bosch_ldi_bike_not_driving",
    "bosch_ldi_connection"
)

private fun fieldLabel(fieldId: String): Int = when (fieldId) {
    "bosch_ldi_speed" -> R.string.field_speed_name
    "bosch_ldi_cadence" -> R.string.field_cadence_name
    "bosch_ldi_rider_power" -> R.string.field_rider_power_name
    "bosch_ldi_battery_soc" -> R.string.field_battery_soc_name
    "bosch_ldi_odometer" -> R.string.field_odometer_name
    "bosch_ldi_time" -> R.string.field_time_name
    "bosch_ldi_bike_light" -> R.string.field_bike_light_name
    "bosch_ldi_ambient_brightness" -> R.string.field_ambient_brightness_name
    "bosch_ldi_light_reserve_state" -> R.string.field_light_reserve_name
    "bosch_ldi_system_locked" -> R.string.field_system_locked_name
    "bosch_ldi_charger_connected" -> R.string.field_charger_connected_name
    "bosch_ldi_diagnosis_program_active" -> R.string.field_diagnosis_name
    "bosch_ldi_bike_not_driving" -> R.string.field_bike_not_driving_name
    "bosch_ldi_connection" -> R.string.field_connection_name
    else -> R.string.app_name
}

@Composable
fun BikeDetailScreen(
    viewModel: BikeDetailViewModel,
    onBack: () -> Unit
) {
    val isActive by viewModel.isActive.collectAsState()
    val enabledFields by viewModel.enabledFields.collectAsState()
    var showForgetDialog by remember { mutableStateOf(false) }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.bike_detail_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.bike_detail_remove_confirm_body,
                        viewModel.slot.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgetDialog = false
                    viewModel.forget()
                    onBack()
                }) { Text(stringResource(R.string.bike_detail_remove_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text(stringResource(R.string.bike_detail_remove_confirm_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.slot.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.bike_detail_active_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(checked = isActive, onCheckedChange = { viewModel.toggleActive() })
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.bike_detail_fields_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.bike_detail_fields_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(ALL_FIELDS) { fieldId ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = fieldId in enabledFields,
                        onCheckedChange = { viewModel.toggleField(fieldId) }
                    )
                    Text(
                        text = stringResource(fieldLabel(fieldId)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                Button(
                    onClick = { viewModel.save(); onBack() },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.bike_detail_btn_save))
                }
                Button(
                    onClick = { showForgetDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.bike_detail_btn_remove))
                }
            }
        }
    }
}
