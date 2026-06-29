package de.dxmedia.bosch.ldi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeRepository
import de.dxmedia.bosch.ldi.data.BikeSlot
import de.dxmedia.bosch.ldi.extension.BoschLiveDataService
import de.dxmedia.bosch.ldi.ui.bikes.BikeDetailScreen
import de.dxmedia.bosch.ldi.ui.bikes.BikeDetailViewModel
import de.dxmedia.bosch.ldi.ui.bikes.BikeListScreen
import de.dxmedia.bosch.ldi.ui.bikes.BikeListViewModel
import de.dxmedia.bosch.ldi.ui.settings.SettingsScreen
import de.dxmedia.bosch.ldi.ui.wizard.PairingWizardScreen
import de.dxmedia.bosch.ldi.ui.wizard.PairingWizardViewModel
import de.dxmedia.bosch.ldi.util.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        val lang = LocaleHelper.getStoredLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val missing = blePermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Requesting BLE permissions failed", e)
        }

        val repository = try {
            BikeRepository(this)
        } catch (e: Exception) {
            // Last line of defence: never leave the user on a dead grey window.
            Log.e(TAG, "Repository init failed — showing error screen", e)
            setContent {
                MaterialTheme {
                    Surface {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.startup_error),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            return
        }

        setContent {
            MaterialTheme {
                Surface {
                    val service by BoschLiveDataService.instanceFlow.collectAsState()
                    val bleState by (service?.connectionState
                        ?: MutableStateFlow(BleState.Disconnected)).collectAsState()
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "bikes") {

                        composable("bikes") {
                            val vm = remember { BikeListViewModel(repository) }
                            BikeListScreen(
                                viewModel = vm,
                                bleState = bleState,
                                onPairSlot = { slot ->
                                    navController.navigate("bikes/${slot.name}/wizard")
                                },
                                onEditSlot = { slot ->
                                    navController.navigate("bikes/${slot.name}/detail")
                                },
                                onNavigateSettings = { navController.navigate("settings") }
                            )
                        }

                        composable("bikes/{slot}/wizard") { backStackEntry ->
                            val slotName = backStackEntry.arguments?.getString("slot")
                                ?: return@composable
                            val slot = BikeSlot.valueOf(slotName)
                            val svc = service ?: return@composable
                            val vm = remember(slot) {
                                PairingWizardViewModel(
                                    slot = slot,
                                    connectionState = svc.connectionState,
                                    onStartPairing = { svc.startPairing(slot) }
                                )
                            }
                            PairingWizardScreen(
                                viewModel = vm,
                                onDone = {
                                    val connState = svc.connectionState.value
                                    if (connState is BleState.Connected) {
                                        svc.onPairingSuccess(slot, connState.deviceAddress)
                                    }
                                    navController.popBackStack("bikes", inclusive = false)
                                },
                                onCancel = { navController.popBackStack() }
                            )
                        }

                        composable("bikes/{slot}/detail") { backStackEntry ->
                            val slotName = backStackEntry.arguments?.getString("slot")
                                ?: return@composable
                            val slot = BikeSlot.valueOf(slotName)
                            val profile = remember(slot) {
                                repository.getProfiles().firstOrNull { it.slot == slot }
                            } ?: return@composable
                            val vm = remember(slot) {
                                BikeDetailViewModel(
                                    profile = profile,
                                    onSave = { updated ->
                                        repository.upsert(updated)
                                        service?.reloadActiveProfile()
                                    },
                                    onForget = { s ->
                                        repository.delete(s)
                                        service?.setActiveSlot(
                                            repository.getActiveProfile()?.slot ?: BikeSlot.ALPHA
                                        )
                                    },
                                    onSetActive = { s -> service?.setActiveSlot(s) }
                                )
                            }
                            BikeDetailScreen(
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onLanguageChange = { lang ->
                                    LocaleHelper.setLanguage(this@MainActivity, lang)
                                    recreate()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
