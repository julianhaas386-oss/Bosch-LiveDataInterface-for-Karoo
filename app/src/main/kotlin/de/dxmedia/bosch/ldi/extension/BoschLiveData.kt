package de.dxmedia.bosch.ldi.extension

enum class LightState { OFF, ON }

data class BoschLiveData(
    val speedCmPerHour: Int? = null,
    val cadenceRpm: Int? = null,
    val riderPowerW: Int? = null,
    val ambientBrightnessMilliLux: Int? = null,
    val batterySocPercent: Int? = null,
    val timeUtcSeconds: Long? = null,
    val odometerMeters: Int? = null,
    val bikeLight: LightState? = null,
    val systemLocked: Boolean? = null,
    val chargerConnected: Boolean? = null,
    val lightReserveState: Boolean? = null,
    val diagnosisProgramActive: Boolean? = null,
    val bikeNotDriving: Boolean? = null
)
