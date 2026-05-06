package de.dxmedia.bosch.ldi.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
import kotlinx.coroutines.launch

data class DashboardData(
    val soc: String,
    val power: String,
    val cadence: String,
    val speed: String,
    val odometer: String
)

fun formatDashboard(data: BoschLiveData?): DashboardData = DashboardData(
    soc = data?.batterySocPercent?.let { "$it %" } ?: "—",
    power = data?.riderPowerW?.let { "$it W" } ?: "—",
    cadence = data?.cadenceRpm?.let { "$it rpm" } ?: "—",
    speed = data?.speedCmPerHour?.let { "${it / 100} km/h" } ?: "—",
    odometer = data?.odometerMeters?.let { "Odo: ${it / 1000} km" } ?: "Odo: —"
)

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class EBikePage(
    private val liveData: StateFlow<BoschLiveData?>
) : DataTypeImpl("bosch-ldi", "bosch_ldi_ebike_dashboard") {

    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            liveData.collect {
                emitter.onNext(
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0)))
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            liveData.collect { data ->
                val fmt = formatDashboard(data)
                val result = glance.compose(context, DpSize.Unspecified) {
                    DashboardContent(fmt, config.textSize)
                }
                emitter.updateView(result.remoteViews)
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun DashboardContent(fmt: DashboardData, textSize: Int) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(text = "SOC", style = TextStyle(fontSize = (textSize * 0.65f).sp))
                Text(text = fmt.soc, style = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Bold))
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(text = "Power", style = TextStyle(fontSize = (textSize * 0.65f).sp))
                Text(text = fmt.power, style = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Bold))
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(text = "Cadence", style = TextStyle(fontSize = (textSize * 0.65f).sp))
                Text(text = fmt.cadence, style = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Bold))
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(text = "Speed", style = TextStyle(fontSize = (textSize * 0.65f).sp))
                Text(text = fmt.speed, style = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Bold))
            }
        }
        Text(
            text = fmt.odometer,
            style = TextStyle(fontSize = (textSize * 0.8f).sp)
        )
    }
}
