package app.powerhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powerhub.PowerHubApp
import app.powerhub.data.DeveloperKeys
import app.powerhub.data.SyncResult
import kotlinx.coroutines.launch
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
            DeveloperKeysCard()
            HiddenStationsCard()
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

@Composable
private fun DeveloperKeysCard() {
    val repo = PowerHubApp.repo
    val scope = rememberCoroutineScope()
    val saved = remember { repo.developerKeys() }
    var accessKey by remember { mutableStateOf(saved?.accessKey.orEmpty()) }
    var secretKey by remember { mutableStateOf(saved?.secretKey.orEmpty()) }
    var hasKeys by remember { mutableStateOf(saved != null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Список станцій з акаунта", style = MaterialTheme.typography.titleSmall)
            Text(
                "Ключі з developer.ecoflow.com. З ними станції підтягуються автоматично й однаково на всіх телефонах.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                accessKey, { accessKey = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text("Access Key") }, singleLine = true,
            )
            OutlinedTextField(
                secretKey, { secretKey = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text("Secret Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            message?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && accessKey.isNotBlank() && secretKey.isNotBlank(),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                val r = repo.saveDeveloperKeys(DeveloperKeys(accessKey, secretKey))
                                hasKeys = true
                                isError = false
                                message = describeSync(r)
                            } catch (e: Exception) {
                                isError = true
                                message = "Не вдалося: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (hasKeys) "Зберегти й оновити" else "Зберегти")
                }
                if (hasKeys) {
                    TextButton(onClick = {
                        repo.clearDeveloperKeys()
                        accessKey = ""
                        secretKey = ""
                        hasKeys = false
                        isError = false
                        message = "Ключі видалено. Станції лишилися в списку."
                    }) { Text("Видалити ключі") }
                }
            }
        }
    }
}

fun describeSync(r: SyncResult): String = buildString {
    append("Синхронізовано: нових ${r.added}, оновлено ${r.updated}, прибрано ${r.removed}.")
    if (r.unsupported.isNotEmpty()) {
        append(" Поки не підтримуються: ${r.unsupported.joinToString()}.")
    }
}

@Composable
private fun HiddenStationsCard() {
    val repo = PowerHubApp.repo
    val hidden by repo.settings.hidden.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (hidden.isEmpty()) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Видалені станції", style = MaterialTheme.typography.titleSmall)
            Text(
                "Є в акаунті EcoFlow, але приховані в PowerHub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hidden.forEach { (sn, name) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name)
                        Text(sn, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    TextButton(onClick = { scope.launch { runCatching { repo.restoreStation(sn) } } }) {
                        Text("Повернути")
                    }
                }
            }
        }
    }
}
