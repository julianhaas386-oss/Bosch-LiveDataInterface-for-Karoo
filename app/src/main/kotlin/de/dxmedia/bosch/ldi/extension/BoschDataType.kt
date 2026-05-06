package de.dxmedia.bosch.ldi.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class BoschDataType(
    private val liveData: StateFlow<BoschLiveData?>,
    typeId: String,
    internal val extract: BoschLiveData.() -> Double?
) : DataTypeImpl("bosch-ldi", typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            liveData.filterNotNull().collect { data ->
                val value = data.extract()
                emitter.onNext(
                    if (value != null)
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))
                    else
                        StreamState.NotAvailable
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        fun allTypes(liveData: StateFlow<BoschLiveData?>): List<BoschDataType> = listOf(
            BoschDataType(liveData, "bosch_ldi_speed") {
                speedCmPerHour?.div(100.0)
            },
            BoschDataType(liveData, "bosch_ldi_cadence") {
                cadenceRpm?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_rider_power") {
                riderPowerW?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_ambient_brightness") {
                ambientBrightnessMilliLux?.div(1000.0)
            },
            BoschDataType(liveData, "bosch_ldi_battery_soc") {
                batterySocPercent?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_time") {
                timeUtcSeconds?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_odometer") {
                odometerMeters?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_bike_light") {
                bikeLight?.let { if (it == LightState.ON) 1.0 else 0.0 }
            },
            BoschDataType(liveData, "bosch_ldi_system_locked") {
                systemLocked?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_charger_connected") {
                chargerConnected?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_light_reserve_state") {
                lightReserveState?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_diagnosis_program_active") {
                diagnosisProgramActive?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_bike_not_driving") {
                bikeNotDriving?.toDouble()
            },
        )
    }
}

private fun Boolean.toDouble() = if (this) 1.0 else 0.0
