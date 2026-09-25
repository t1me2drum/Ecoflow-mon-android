package app.powerhub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powerhub.PowerHubApp
import app.powerhub.data.DeviceSnapshot
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val repo = PowerHubApp.repo
    val devices by repo.settings.devices.collectAsStateWithLifecycle()
    val snapshots by repo.snapshots.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Device?>(null) }
    var removing by remember { mutableStateOf<Device?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun sync() {
        if (syncing) return
        syncing = true
        scope.launch {
            val text = try {
                describeSync(repo.syncStations())
            } catch (e: Exception) {
                "Не вдалося оновити список: ${e.message}"
            } finally {
                syncing = false
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мої станції") },
                actions = {
                    if (repo.hasDeveloperKeys) {
                        IconButton(onClick = { sync() }, enabled = !syncing) {
                            Icon(Icons.Default.Sync, "Оновити список станцій")
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Налаштування") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                        "Додайте ключі Developer API в налаштуваннях, щоб підтягнути всі станції акаунта, " +
                            "або натисніть «+» і введіть серійний номер вручну.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Compact cards (~76dp) so six stations fit on one screen; bottom padding keeps the last card clear of the FAB.
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.sn }) { d ->
                    DeviceCard(
                        d, snapshots[d.sn],
                        onClick = { onOpen(d.sn) },
                        onRename = { renaming = d },
                        onRemove = { removing = d },
                    )
                }
            }
        }
    }

    if (adding) AddDeviceDialog(onDismiss = { adding = false }, onAdd = { repo.settings.addDevice(it); adding = false })

    renaming?.let { d ->
        RenameDialog(d.name, onDismiss = { renaming = null }) { repo.settings.renameDevice(d.sn, it); renaming = null }
    }

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
private fun DeviceCard(
    d: Device,
    snap: DeviceSnapshot?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = d.model.protocol.state(snap?.params.orEmpty())
    val online = snap?.isOnline() == true
    var menu by remember { mutableStateOf(false) }

    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SocRing(state.soc, online, 56.dp, 6.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "  ${d.model.title}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    val status = when {
                        !online -> if (snap == null) "Очікування даних…" else "Не на зв'язку"
                        else -> {
                            val grid = when (state.gridConnected) {
                                true -> " · мережа ✓"
                                false -> " · без мережі"
                                null -> ""
                            }
                            "↓ ${watts(state.inputW)} · ↑ ${watts(state.outputW)}$grid"
                        }
                    }
                    Text(
                        status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Перейменувати") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Видалити") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; onRemove() },
            )
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
