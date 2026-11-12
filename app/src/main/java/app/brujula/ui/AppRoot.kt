package app.brujula.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppRoot(c: UiController, context: Context) {
    // Cada cambio de datos vuelve a calcular las pantallas fuera del hilo principal.
    LaunchedEffect(c.container.version.value) { c.reload() }
    BackHandler(enabled = c.sub.value !is SubScreen.None) { c.sub.value = SubScreen.None }

    val d = c.data.value
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (c.sub.value is SubScreen.None) {
                    NavigationBar {
                        Tab.values().forEach { t ->
                            NavigationBarItem(
                                selected = c.tab.value == t, onClick = { c.tab.value = t },
                                icon = { Text(t.glyph) }, label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Header(c)
                c.message.value?.let { msg ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) { Notice(msg) }
                        TextButton(onClick = { c.message.value = null }) { Text("OK") }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        d.error != null -> ErrorScreen(d.error)
                        d.loading -> Text("Cargando…", modifier = Modifier.padding(16.dp))
                        else -> Content(c, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(c: UiController, context: Context) {
    val d = c.data.value
    when (val s = c.sub.value) {
        is SubScreen.Asset -> AssetDetailScreen(c.detail.value)
        SubScreen.Accounts -> AccountsScreen(c)
        SubScreen.Diagnostics -> DiagnosticsScreen(c, context)
        SubScreen.None -> when (c.tab.value) {
            Tab.HOME -> HomeScreen(d) { c.sub.value = SubScreen.Accounts }
            Tab.PORTFOLIO -> PortfolioScreen(d)
            Tab.MARKETS -> MarketsScreen(d) { c.openAsset(it) }
            Tab.MORE -> MoreScreen(c)
        }
    }
}

@Composable
private fun ErrorScreen(error: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("No se pudieron abrir los datos guardados", style = MaterialTheme.typography.titleMedium)
        Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            "Brújula no descarta ni borra datos en silencio. No desinstales la app: pásale este mensaje al desarrollador para repararlo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Header(c: UiController) {
    val d = c.data.value
    val menu = remember { mutableStateOf(false) }
    val sub = c.sub.value
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sub !is SubScreen.None) {
            TextButton(onClick = { c.sub.value = SubScreen.None }) { Text("← Atrás") }
        } else {
            Text("Brújula", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        val active = d.accounts.filter { !it.archived }
        if (sub is SubScreen.None && active.isNotEmpty()) {
            Box {
                TextButton(onClick = { menu.value = true }) { Text((d.accounts.firstOrNull { it.id == d.selectedId }?.name ?: "Cuenta") + " ▾") }
                DropdownMenu(expanded = menu.value, onDismissRequest = { menu.value = false }) {
                    active.forEach { a ->
                        DropdownMenuItem(text = { Text(a.name) }, onClick = { menu.value = false; c.select(a.id) })
                    }
                }
            }
        }
    }
}
