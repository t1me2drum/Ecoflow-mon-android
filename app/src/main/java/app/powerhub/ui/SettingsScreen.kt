package app.powerhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powerhub.PowerHubApp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val repo = PowerHubApp.repo
    val alerts by repo.settings.alerts.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionBanner(conn)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Сповіщення", style = MaterialTheme.typography.titleSmall)
                    SwitchRow("Зникло або з'явилося живлення", alerts.grid) {
                        repo.settings.saveAlerts(alerts.copy(grid = it))
                    }
                    SwitchRow("Низький заряд", alerts.lowBattery) {
                        repo.settings.saveAlerts(alerts.copy(lowBattery = it))
                    }
                    if (alerts.lowBattery) {
                        Text("Поріг: ${alerts.lowBatteryPercent}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = alerts.lowBatteryPercent.toFloat(),
                            onValueChange = { repo.settings.saveAlerts(alerts.copy(lowBatteryPercent = it.roundToInt())) },
                            valueRange = 5f..50f,
                            steps = 8,
                        )
                    }
                    SwitchRow("Повністю заряджено", alerts.fullCharge) {
                        repo.settings.saveAlerts(alerts.copy(fullCharge = it))
                    }
                    SwitchRow("Станція не на зв'язку", alerts.offline) {
                        repo.settings.saveAlerts(alerts.copy(offline = it))
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Про застосунок", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Фоновий моніторинг тримає постійне з'єднання, щоб сповіщення приходили вчасно. " +
                            "Якщо сповіщення запізнюються, вимкніть оптимізацію батареї для PowerHub у налаштуваннях Android.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Протокол взято з відкритого проєкту tolwi/hassio-ecoflow-cloud (Apache-2.0). " +
                            "Це неофіційний клієнт, EcoFlow може змінити API без попередження.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = { repo.logout(); onLoggedOut() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Вийти з облікового запису") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
