package app.powerhub.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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

/**
 * Battery ring. When [charging] (station on grid power) a bright segment runs along the filled
 * arc and a bolt pulses above the percentage; at 100% only the bolt stays.
 */
@Composable
fun SocRing(soc: Int?, online: Boolean, size: Dp, stroke: Dp = 12.dp, charging: Boolean = false) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = when {
        !online -> MaterialTheme.colorScheme.outline
        (soc ?: 0) <= 20 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val animate = charging && online && soc != null
    val transition = rememberInfiniteTransition(label = "charging")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = s)
            if (soc != null) {
                val filled = 270f * soc.coerceIn(0, 100) / 100f
                drawArc(color, 135f, filled, false, topLeft, arcSize, style = s)
                if (animate && soc < 100 && filled > 0f) {
                    // A short highlight travelling from the empty end towards the current level.
                    val len = minOf(40f, filled)
                    val head = (filled + len) * sweep
                    val from = (head - len).coerceAtLeast(0f)
                    val to = head.coerceAtMost(filled)
                    if (to > from) {
                        drawArc(
                            Color.White.copy(alpha = 0.55f), 135f + from, to - from, false, topLeft, arcSize,
                            style = s,
                        )
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (animate) {
                Icon(
                    Icons.Default.Bolt, "Заряджається",
                    Modifier.size(size * 0.2f).alpha(if (soc!! < 100) pulse else 1f),
                    tint = color,
                )
            }
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
