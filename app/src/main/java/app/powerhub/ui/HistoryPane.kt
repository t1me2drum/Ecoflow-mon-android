package app.powerhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.powerhub.PowerHubApp
import app.powerhub.data.Sample
import app.powerhub.protocol.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RANGES = listOf("6 год" to 6L, "24 год" to 24L, "7 днів" to 24L * 7, "30 днів" to 24L * 30)

@Composable
fun HistoryPane(device: Device) {
    val repo = PowerHubApp.repo
    var range by rememberSaveable { mutableIntStateOf(1) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }

    LaunchedEffect(device.sn, range) {
        while (true) {
            val from = System.currentTimeMillis() - RANGES[range].second * 3600_000L
            samples = withContext(Dispatchers.IO) { repo.history.query(device.sn, from) }
            delay(60_000)
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEachIndexed { i, (label, _) ->
                FilterChip(selected = range == i, onClick = { range = i }, label = { Text(label) })
            }
        }
        if (samples.size < 2) {
            Text(
                "Історія ще збирається. Точки записуються щохвилини, поки працює фоновий моніторинг.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.tertiary
        val solar = Color(0xFFF2A900)

        ChartCard("Рівень заряду, %", samples, listOf(Series("Заряд", primary) { it.soc }), fixedMax = 100f)
        ChartCard(
            "Потужність, Вт", samples,
            listOf(
                Series("Вхід", primary) { it.inputW },
                Series("Вихід", secondary) { it.outputW },
                Series("Сонце", solar) { it.solarW },
            ),
        )

        // Each sample covers one minute, so W / 60 gives Wh.
        val inWh = samples.sumOf { (it.inputW ?: 0).toDouble() } / 60
        val outWh = samples.sumOf { (it.outputW ?: 0).toDouble() } / 60
        val solarWh = samples.sumOf { (it.solarW ?: 0).toDouble() } / 60
        val outageMin = samples.count { it.grid == false }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Енергія за період", style = MaterialTheme.typography.titleSmall)
                EnergyRow("Отримано", inWh)
                EnergyRow("Спожито", outWh)
                EnergyRow("З сонця", solarWh)
                Row(Modifier.fillMaxWidth()) {
                    Text("Без мережі", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(minutes(outageMin))
                }
            }
        }
    }
}

@Composable
private fun EnergyRow(label: String, wh: Double) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (wh >= 1000) "%.2f кВт·год".format(wh / 1000) else "%.0f Вт·год".format(wh))
    }
}

private class Series(val name: String, val color: Color, val value: (Sample) -> Int?)

@Composable
private fun ChartCard(title: String, samples: List<Sample>, series: List<Series>, fixedMax: Float? = null) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val t0 = samples.first().ts
    val t1 = samples.last().ts
    val maxValue = fixedMax ?: (samples.maxOf { s -> series.maxOf { (it.value(s) ?: 0) } }.toFloat() * 1.1f).coerceAtLeast(10f)
    val fmt = SimpleDateFormat(if (t1 - t0 > 36 * 3600_000L) "dd.MM" else "HH:mm", Locale.getDefault())

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("макс ${maxValue.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val w = size.width
                val h = size.height
                for (i in 0..4) {
                    val y = h * i / 4
                    drawLine(grid, Offset(0f, y), Offset(w, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                val span = (t1 - t0).coerceAtLeast(1L).toFloat()
                for (s in series) {
                    val path = Path()
                    var prevTs = Long.MIN_VALUE
                    var drawing = false
                    for (sample in samples) {
                        val v = s.value(sample)
                        // Break the line on gaps (service stopped / device offline).
                        if (v == null || sample.ts - prevTs > 5 * 60_000L) drawing = false
                        prevTs = sample.ts
                        if (v == null) continue
                        val x = (sample.ts - t0) / span * w
                        val y = h - (v / maxValue).coerceIn(0f, 1f) * h
                        if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                        drawing = true
                    }
                    drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Row {
                Text(fmt.format(Date(t0)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(fmt.format(Date(t1)), style = MaterialTheme.typography.labelSmall)
            }
            if (series.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    series.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(s.color, CircleShape))
                            Text(" ${s.name}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
