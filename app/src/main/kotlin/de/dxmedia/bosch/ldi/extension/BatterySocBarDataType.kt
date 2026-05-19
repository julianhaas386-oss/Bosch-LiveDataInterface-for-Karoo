package de.dxmedia.bosch.ldi.extension

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import de.dxmedia.bosch.ldi.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class BatterySocBarDataType(
    private val liveData: StateFlow<BoschLiveData?>
) : DataTypeImpl("bosch-ldi", "bosch_ldi_battery_bar") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 75.0))))
            liveData.filterNotNull().collect { data ->
                val soc = data.batterySocPercent
                emitter.onNext(
                    if (soc != null)
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to soc.toDouble())))
                    else
                        StreamState.NotAvailable
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (config.preview) {
            emitter.updateView(renderBar(context, 75, config.textSize))
            emitter.setCancellable {}
            return
        }
        val job = CoroutineScope(Dispatchers.Default).launch {
            liveData.collect { data ->
                emitter.updateView(renderBar(context, data?.batterySocPercent, config.textSize))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun renderBar(context: Context, soc: Int?, textSizeSp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.view_battery_bar)
        val socVal = soc?.coerceIn(0, 100) ?: 0

        val greenVis = if (soc == null || socVal > 50) View.VISIBLE else View.GONE
        val yellowVis = if (soc != null && socVal in 21..50) View.VISIBLE else View.GONE
        val redVis = if (soc != null && socVal <= 20) View.VISIBLE else View.GONE

        views.setViewVisibility(R.id.bar_green, greenVis)
        views.setViewVisibility(R.id.bar_yellow, yellowVis)
        views.setViewVisibility(R.id.bar_red, redVis)
        views.setProgressBar(R.id.bar_green, 100, socVal, false)
        views.setProgressBar(R.id.bar_yellow, 100, socVal, false)
        views.setProgressBar(R.id.bar_red, 100, socVal, false)

        views.setTextViewText(R.id.battery_text, if (soc != null) "$soc %" else "—")
        views.setTextViewTextSize(R.id.battery_text, TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())

        return views
    }
}
