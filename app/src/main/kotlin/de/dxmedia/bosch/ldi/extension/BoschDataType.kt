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
    private val sampleValue: Double?,
    internal val extract: BoschLiveData.() -> Double?
) : DataTypeImpl("bosch-ldi", typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            // Emit a sample value immediately so the field shows data in the Karoo preview picker.
            sampleValue?.let {
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))))
            }
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
            BoschDataType(liveData, "bosch_ldi_speed", 25.0) {
                speedCmPerHour?.div(100.0)
            },
            BoschDataType(liveData, "bosch_ldi_cadence", 80.0) {
                cadenceRpm?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_rider_power", 200.0) {
                riderPowerW?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_ambient_brightness", 15.0) {
                ambientBrightnessMilliLux?.div(1000.0)
            },
            BoschDataType(liveData, "bosch_ldi_battery_soc", 75.0) {
                batterySocPercent?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_time", null) {
                timeUtcSeconds?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_odometer", 12500.0) {
                odometerMeters?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_bike_light", 1.0) {
                bikeLight?.let { if (it == LightState.ON) 1.0 else 0.0 }
            },
            BoschDataType(liveData, "bosch_ldi_system_locked", 0.0) {
                systemLocked?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_charger_connected", 0.0) {
                chargerConnected?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_light_reserve_state", 0.0) {
                lightReserveState?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_diagnosis_program_active", 0.0) {
                diagnosisProgramActive?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_bike_not_driving", 0.0) {
                bikeNotDriving?.toDouble()
            },
        )
    }
}

private fun Boolean.toDouble() = if (this) 1.0 else 0.0
