package app.powerhub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powerhub.PowerHubApp
import app.powerhub.data.DeviceSnapshot
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val repo = PowerHubApp.repo
    val devices by repo.settings.devices.collectAsStateWithLifecycle()
    val snapshots by repo.snapshots.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Device?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мої станції") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Налаштування") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Додати") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(conn)
            if (devices.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text("Станцій ще немає", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Натисніть «+» і введіть серійний номер. Його видно в офіційному застосунку " +
                            "(Налаштування пристрою → Про пристрій) або на наклейці станції.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(devices, key = { it.sn }) { d ->
                    DeviceCard(d, snapshots[d.sn], onClick = { onOpen(d.sn) }, onLongClick = { removing = d })
                }
            }
        }
    }

    if (adding) AddDeviceDialog(onDismiss = { adding = false }, onAdd = { repo.settings.addDevice(it); adding = false })

    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Видалити ${d.name}?") },
            text = { Text("Станцію буде прибрано зі списку. Історія лишиться на телефоні.") },
            confirmButton = {
                TextButton(onClick = { repo.settings.removeDevice(d.sn); removing = null }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Скасувати") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(d: Device, snap: DeviceSnapshot?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val state = d.model.protocol.state(snap?.params.orEmpty())
    val online = snap?.isOnline() == true
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SocRing(state.soc, online, 84.dp, 8.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(d.model.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (online) {
                    Text("Вхід ${watts(state.inputW)} · Вихід ${watts(state.outputW)}", style = MaterialTheme.typography.bodyMedium)
                    val grid = when (state.gridConnected) {
                        true -> "Мережа є"
                        false -> "Немає мережі"
                        null -> null
                    }
                    grid?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(
                        if (snap == null) "Очікування даних…" else "Не на зв'язку",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddDeviceDialog(onDismiss: () -> Unit, onAdd: (Device) -> Unit) {
    var sn by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(DeviceModel.DELTA_2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Нова станція") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(sn, { sn = it.trim().uppercase() }, label = { Text("Серійний номер") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Назва (необов'язково)") }, singleLine = true)
                Text("Модель", style = MaterialTheme.typography.labelLarge)
                DeviceModel.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m -> FilterChip(model == m, { model = m }, label = { Text(m.title) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = sn.length >= 8,
                onClick = { onAdd(Device(sn, name.ifBlank { model.title }, model)) },
            ) { Text("Додати") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
