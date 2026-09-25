package app.powerhub.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.powerhub.PowerHubApp
import app.powerhub.ui.MainActivity
import org.json.JSONArray

private data class WidgetRow(val name: String, val soc: Int, val inW: Int, val outW: Int, val online: Boolean, val grid: Boolean)

class BatteryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = parse(PowerHubApp.repo.settings.widgetCache)
        provideContent {
            GlanceTheme { Content(rows) }
        }
    }

    private fun parse(json: String?): List<WidgetRow> = runCatching {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WidgetRow(
                o.getString("name"), o.getInt("soc"), o.getInt("in"), o.getInt("out"),
                o.getBoolean("online"), o.optBoolean("grid"),
            )
        }
    }.getOrDefault(emptyList())

    @Composable
    private fun Content(rows: List<WidgetRow>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (rows.isEmpty()) {
                Text("Немає даних", style = TextStyle(color = GlanceTheme.colors.onSurface))
                return@Column
            }
            rows.take(2).forEachIndexed { i, r ->
                if (i > 0) Spacer(GlanceModifier.height(8.dp))
                Text(r.name, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                Text(
                    if (r.soc >= 0) "${r.soc}%" else "—",
                    style = TextStyle(
                        color = if (r.online) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                        fontSize = if (rows.size == 1) 36.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                val status = when {
                    !r.online -> "не на зв'язку"
                    r.grid -> "мережа ✓ ↓${r.inW} ↑${r.outW} Вт"
                    else -> "батарея ↑${r.outW} Вт"
                }
                Text(status, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp))
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = BatteryWidget().updateAll(context)
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}
