package app.powerhub.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.powerhub.PowerHubApp
import app.powerhub.service.MonitorService

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val repo = PowerHubApp.repo
        if (repo.isLoggedIn) MonitorService.start(this)

        setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = if (repo.isLoggedIn) "devices" else "login") {
                    composable("login") {
                        LoginScreen(onLoggedIn = {
                            MonitorService.start(this@MainActivity)
                            nav.navigate("devices") { popUpTo("login") { inclusive = true } }
                        })
                    }
                    composable("devices") {
                        DevicesScreen(
                            onOpen = { sn -> nav.navigate("device/$sn") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("device/{sn}") { entry ->
                        DeviceScreen(sn = entry.arguments?.getString("sn").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onLoggedOut = {
                                MonitorService.stop(this@MainActivity)
                                nav.navigate("login") { popUpTo(0) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF4FD1B5))
        else -> lightColorScheme(primary = Color(0xFF0E7C66))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
