package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConnectionDataType(
    private val bleState: StateFlow<BleState>
) : DataTypeImpl("bosch-ldi", "bosch_ldi_connection") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            bleState.collect { state ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to state.toConnectionDouble()))
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        internal fun BleState.toConnectionDouble(): Double = when (this) {
            is BleState.Connected -> 2.0
            is BleState.Advertising -> 1.0
            is BleState.Disconnected -> 0.0
        }
    }
}
