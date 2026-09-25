package app.powerhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.powerhub.data.ConnState

fun watts(w: Int?): String = w?.let { if (it >= 1000) "%.2f кВт".format(it / 1000.0) else "$it Вт" } ?: "—"

fun minutes(m: Int?): String {
    if (m == null) return "—"
    val h = m / 60
    val mm = m % 60
    return if (h > 0) "$h год $mm хв" else "$mm хв"
}

@Composable
fun SocRing(soc: Int?, online: Boolean, size: Dp, stroke: Dp = 12.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = when {
        !online -> MaterialTheme.colorScheme.outline
        (soc ?: 0) <= 20 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = s)
            if (soc != null) drawArc(color, 135f, 270f * soc.coerceIn(0, 100) / 100f, false, topLeft, arcSize, style = s)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                soc?.let { "$it%" } ?: "—",
                fontSize = (size.value / 4.2f).sp,
                fontWeight = FontWeight.Bold,
            )
            if (!online) Text("офлайн", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Назва станції") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Назва") }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name.trim()) }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
fun DeleteStationDialog(device: app.powerhub.protocol.Device, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Видалити ${device.name}?") },
        text = {
            Text(
                if (device.imported) {
                    "Станцію буде прибрано лише з PowerHub на цьому телефоні. В акаунті EcoFlow і в офіційному " +
                        "застосунку вона лишиться. Під час синхронізації вона не повернеться; відновити можна в Налаштуваннях."
                } else {
                    "Станцію буде прибрано лише з PowerHub на цьому телефоні. В акаунті EcoFlow вона лишиться."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
fun ConnectionBanner(state: ConnState) {
    val text = when (state) {
        ConnState.Connected, ConnState.Idle -> return
        ConnState.Connecting -> "Підключення до хмари…"
        is ConnState.Reconnecting -> "Перепідключення: ${state.reason}"
        is ConnState.Failed -> "Помилка: ${state.reason}. Перевірте логін у налаштуваннях."
    }
    val failed = state is ConnState.Failed
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}
