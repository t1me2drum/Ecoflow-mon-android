package app.powerhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powerhub.PowerHubApp
import app.powerhub.protocol.Control
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceState
import app.powerhub.protocol.Params
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(sn: String, onBack: () -> Unit) {
    val repo = PowerHubApp.repo
    val devices by repo.settings.devices.collectAsStateWithLifecycle()
    val snapshots by repo.snapshots.collectAsStateWithLifecycle()
    val device = devices.find { it.sn == sn } ?: return
    val snap = snapshots[sn]
    val params = snap?.params.orEmpty()
    val state = device.model.protocol.state(params)
    val online = snap?.isOnline() == true
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(sn) { repo.requestQuota(device) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Перейменувати") }
                    IconButton(onClick = { repo.requestQuota(device) }) { Icon(Icons.Default.Refresh, "Оновити") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Видалити станцію") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Огляд", "Керування", "Графіки", "Дані").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> Overview(device, state, online)
                1 -> Controls(device, params, online, snackbar)
                2 -> HistoryPane(device)
                3 -> RawData(params)
            }
        }
    }

    if (deleting) {
        DeleteStationDialog(device, onDismiss = { deleting = false }) {
            deleting = false
            onBack()
            repo.settings.removeDevice(device.sn)
        }
    }

    if (renaming) {
        RenameDialog(device.name, onDismiss = { renaming = false }) {
            repo.settings.renameDevice(device.sn, it)
            renaming = false
        }
    }
}

@Composable
private fun Overview(device: Device, s: DeviceState, online: Boolean) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SocRing(s.soc, online, 200.dp)
        }
        val charging = (s.inputW ?: 0) > (s.outputW ?: 0)
        val remainText = when {
            charging && s.chargeRemainMin != null -> "До повного заряду: ${minutes(s.chargeRemainMin)}"
            !charging && s.dischargeRemainMin != null -> "Вистачить на: ${minutes(s.dischargeRemainMin)}"
            else -> null
        }
        remainText?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigStat("Вхід", watts(s.inputW), Modifier.weight(1f))
            BigStat("Вихід", watts(s.outputW), Modifier.weight(1f))
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Джерела", style = MaterialTheme.typography.titleSmall)
                StatRow("Мережа AC", watts(s.acInW))
                StatRow("Сонце", watts(s.solarW))
                StatRow(
                    "Стан мережі",
                    when (s.gridConnected) {
                        true -> "є" + (s.acInVolt?.let { " ($it В)" } ?: "")
                        false -> "немає"
                        null -> "—"
                    },
                )
                HorizontalDivider()
                Text("Споживачі", style = MaterialTheme.typography.titleSmall)
                StatRow("AC виходи", watts(s.acOutW))
                StatRow("DC 12V", watts(s.dcOutW))
                StatRow("USB / Type-C", watts(s.usbOutW))
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Батарея", style = MaterialTheme.typography.titleSmall)
                StatRow("Температура", s.batteryTempC?.let { "$it °C" } ?: "—")
                StatRow("Стан (SOH)", s.soh?.let { "$it%" } ?: "—")
                StatRow("Цикли", s.cycles?.toString() ?: "—")
                StatRow("Модель", device.model.title)
                StatRow("Серійний номер", device.sn)
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun Controls(device: Device, params: Params, online: Boolean, snackbar: SnackbarHostState) {
    val repo = PowerHubApp.repo
    val scope = rememberCoroutineScope()
    val controls = remember(device) { device.model.protocol.controls(device.sn) }
    fun report(ok: Boolean) {
        if (!ok) scope.launch { snackbar.showSnackbar("Немає з'єднання з хмарою") }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!online) {
            Text(
                "Станція не на зв'язку — команди можуть не дійти.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        controls.groupBy { it.section }.forEach { (section, items) ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleSmall)
                    items.forEach { c ->
                        when (c) {
                            is Control.Toggle -> ToggleRow(c, params) { report(repo.toggle(device, c, it)) }
                            is Control.Slider -> SliderRow(c, params) { report(repo.setValue(device, c, it)) }
                            is Control.Choice -> ChoiceRow(c, params) { report(repo.choose(device, c, it)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(c: Control.Toggle, params: Params, onChange: (Boolean) -> Unit) {
    val value = c.read(params)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(c.label, Modifier.weight(1f))
        if (value == null) Text("—  ", color = MaterialTheme.colorScheme.outline)
        Switch(checked = value == true, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(c: Control.Slider, params: Params, onCommit: (Int) -> Unit) {
    val current = c.read(params)
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(c.min.toFloat()) }
    val shown = if (dragging) local else (current ?: c.min).toFloat()
    val steps = ((c.max - c.min) / c.step - 1).coerceAtLeast(0)

    Column {
        Row {
            Text(c.label, Modifier.weight(1f))
            Text(if (current == null && !dragging) "—" else "${shown.roundToInt()} ${c.unit}")
        }
        Slider(
            value = shown,
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = {
                val snapped = (c.min + ((local - c.min) / c.step).roundToInt() * c.step).coerceIn(c.min, c.max)
                dragging = false
                onCommit(snapped)
            },
            valueRange = c.min.toFloat()..c.max.toFloat(),
            steps = if (steps <= 100) steps else 0,
        )
    }
}

@Composable
private fun ChoiceRow(c: Control.Choice, params: Params, onSelect: (Int) -> Unit) {
    val current = c.read(params)
    Column {
        Text(c.label)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            c.options.forEach { (label, v) ->
                FilterChip(selected = current == v, onClick = { onSelect(v) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun RawData(params: Params) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Сирі значення від станції (${params.size}). Корисно, щоб знайти поля, яких ще немає в інтерфейсі.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        params.toSortedMap().forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(k, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(v.toString().take(40), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
