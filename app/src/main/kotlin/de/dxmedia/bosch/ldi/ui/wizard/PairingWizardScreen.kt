package de.dxmedia.bosch.ldi.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R

@Composable
fun PairingWizardScreen(
    viewModel: PairingWizardViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.wizard_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is PairingState.Explaining -> ExplainingStep(
                    slot = viewModel.slot,
                    onStart = { viewModel.startPairing() },
                    onCancel = onCancel
                )
                is PairingState.Advertising -> AdvertisingStep(
                    secondsLeft = s.secondsLeft,
                    onCancel = {
                        viewModel.cancel()
                        onCancel()
                    }
                )
                is PairingState.Success -> SuccessStep(onDone = onDone)
                is PairingState.Failure -> FailureStep(
                    onRetry = { viewModel.startPairing() },
                    onCancel = {
                        viewModel.cancel()
                        onCancel()
                    }
                )
            }
        }
    }
}

@Composable
private fun ExplainingStep(slot: de.dxmedia.bosch.ldi.data.BikeSlot, onStart: () -> Unit, onCancel: () -> Unit) {
    Text(
        text = slot.displayName,
        style = MaterialTheme.typography.headlineSmall
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.wizard_step1_title),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step1_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_security_note),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_start))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_cancel))
    }
}

@Composable
private fun AdvertisingStep(secondsLeft: Int, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.wizard_step2_title),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(16.dp))
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(
        text = "$secondsLeft s",
        style = MaterialTheme.typography.headlineMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step2_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_cancel))
    }
}

@Composable
private fun SuccessStep(onDone: () -> Unit) {
    Text(
        text = stringResource(R.string.wizard_step3_success_title),
        style = MaterialTheme.typography.titleLarge
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step3_success_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_done))
    }
}

@Composable
private fun FailureStep(onRetry: () -> Unit, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.wizard_step3_failure_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step3_failure_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_retry))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_cancel))
    }
}
