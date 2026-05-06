package de.dxmedia.bosch.ldi.extension

import android.util.Log
import com.bosch.ebike.LiveData
import com.bosch.ebike.LightState as ProtoLightState
import com.google.protobuf.CodedInputStream
import de.dxmedia.bosch.ldi.ble.BleManager
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class LiveDataDecoder {

    private val lock = ReentrantReadWriteLock()
    private var current = BoschLiveData()

    fun decode(bytes: ByteArray): BoschLiveData {
        if (bytes.size > BleManager.MAX_PROTO_BYTES) {
            Log.w(TAG, "Payload ${bytes.size}B exceeds limit — returning last known state")
            return lock.read { current }
        }
        val proto = try {
            val input = CodedInputStream.newInstance(bytes)
            input.setSizeLimit(BleManager.MAX_PROTO_BYTES)
            input.setRecursionLimit(16)
            LiveData.parseFrom(input)
        } catch (e: Exception) {
            Log.e(TAG, "Proto parse error — returning last known state", e)
            return lock.read { current }
        }
        return lock.write {
            current = current.copy(
                speedCmPerHour            = if (proto.hasSpeed())                  proto.speed                    else current.speedCmPerHour,
                cadenceRpm                = if (proto.hasCadence())                proto.cadence                  else current.cadenceRpm,
                riderPowerW               = if (proto.hasRiderPower())             proto.riderPower               else current.riderPowerW,
                ambientBrightnessMilliLux = if (proto.hasAmbientBrightness())     proto.ambientBrightness        else current.ambientBrightnessMilliLux,
                batterySocPercent         = if (proto.hasBatterySoc())             proto.batterySoc               else current.batterySocPercent,
                timeUtcSeconds            = if (proto.hasTime())                   proto.time                     else current.timeUtcSeconds,
                odometerMeters            = if (proto.hasOdometer())               proto.odometer                 else current.odometerMeters,
                bikeLight                 = if (proto.hasBikeLight())              proto.bikeLight.toDomain()     else current.bikeLight,
                systemLocked              = if (proto.hasSystemLocked())           proto.systemLocked             else current.systemLocked,
                chargerConnected          = if (proto.hasChargerConnected())       proto.chargerConnected         else current.chargerConnected,
                lightReserveState         = if (proto.hasLightReserveState())      proto.lightReserveState        else current.lightReserveState,
                diagnosisProgramActive    = if (proto.hasDiagnosisProgramActive()) proto.diagnosisProgramActive   else current.diagnosisProgramActive,
                bikeNotDriving            = if (proto.hasBikeNotDriving())         proto.bikeNotDriving           else current.bikeNotDriving
            )
            current
        }
    }

    private fun ProtoLightState.toDomain(): LightState? = when (this) {
        ProtoLightState.LIGHT_STATE_ON  -> LightState.ON
        ProtoLightState.LIGHT_STATE_OFF -> LightState.OFF
        else                            -> null
    }

    companion object {
        private const val TAG = "LiveDataDecoder"
    }
}
