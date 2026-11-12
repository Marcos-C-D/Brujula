package app.brujula.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.brujula.HomeData
import app.brujula.app.Fmt
import app.brujula.engine.Frequency
import app.brujula.engine.OrderSide
import app.brujula.engine.OrderStatus
import app.brujula.engine.OrderType
import app.brujula.engine.ScheduledPlan
import app.brujula.market.AssetKind
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import app.brujula.market.AssetCatalog
import app.brujula.trading.TimelineEntry
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Selector desplegable genérico: muestra la opción elegida y permite cambiarla. */
@Composable
fun <T> Selector(label: String, options: List<Pair<String, T>>, selected: T?, onSelect: (T) -> Unit) {
    val open = remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.second == selected }?.first ?: "Elegir…"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open.value = true }, modifier = Modifier.fillMaxWidth()) { Text("$current ▾") }
        DropdownMenu(expanded = open.value, onDismissRequest = { open.value = false }) {
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open.value = false; onSelect(value) }) }
        }
    }
}

@Composable
fun TimelineView(entries: List<TimelineEntry>) {
    entries.forEach { e ->
        val mark = if (e.done) "✔" else "○"
        val date = e.date?.let { Fmt.date(it) + " · " } ?: ""
        Text("$mark $date${e.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
    }
}

@Composable
fun OperationsScreen(c: UiController) {
    val section = remember { mutableStateOf(0) }
    val d = c.data.value
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Nueva", "Pendientes", "Historial", "Planes").forEachIndexed { i, t ->
                TextButton(onClick = { section.value = i }) { Text(if (section.value == i) "● $t" else t) }
            }
        }
        if (d.selectedId == null) {
            Text("Crea una cuenta para operar (Más › Cuentas).", modifier = Modifier.padding(16.dp))
            return
        }
        when (section.value) {
            0 -> NewOrderSection(c, d)
            1 -> PendingSection(c, d)
            2 -> HistorySection(d)
            else -> PlansSection(c, d)
        }
    }
}

// ------------------------------------------------------------------ Nueva orden

@Composable
private fun NewOrderSection(c: UiController, d: HomeData) {
    val side = remember { mutableStateOf(OrderSide.BUY) }
    val asset = remember { mutableStateOf<AssetId?>(null) }
    val toAsset = remember { mutableStateOf<AssetId?>(null) }
    val type = remember { mutableStateOf(OrderType.MARKET) }
    val amount = remember { mutableStateOf("") }
    val price = remember { mutableStateOf("") }
    val expiryDays = remember { mutableStateOf<Int?>(null) }
    val error = remember { mutableStateOf<String?>(null) }

    val kinds = AssetCatalog.DEFAULT.associate { it.id to it.kind }
    val names = AssetCatalog.DEFAULT.associate { it.id to it.name }
    val buyable = d.markets.filter { it.hasData }.map { it.def.name to it.def.id }
    val sellable = d.sellable.filter { it.value.isPositive }.map { (names[it.key] ?: it.key.value) + " · ${Fmt.units(it.value)} u." to it.key }
    val funds = d.sellable.filter { it.value.isPositive && kinds[it.key] == AssetKind.INDEX_FUND }.map { (names[it.key] ?: it.key.value) to it.key }
    val destinations = AssetCatalog.DEFAULT.filter { it.kind == AssetKind.INDEX_FUND && it.id != asset.value }.map { it.name to it.id }
    val isFund = asset.value?.let { kinds[it] == AssetKind.INDEX_FUND } ?: false

    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(OrderSide.BUY to "Comprar", OrderSide.SELL to "Vender", OrderSide.TRANSFER to "Traspasar").forEach { (s, t) ->
                    OutlinedButton(onClick = { side.value = s; asset.value = null; toAsset.value = null; type.value = OrderType.MARKET; error.value = null }) {
                        Text(if (side.value == s) "● $t" else t)
                    }
                }
            }
            val options = when (side.value) { OrderSide.BUY -> buyable; OrderSide.SELL -> sellable; OrderSide.TRANSFER -> funds }
            if (options.isEmpty()) {
                Text(
                    when (side.value) {
                        OrderSide.BUY -> "Aún no hay precios: actualiza los datos en Más › Diagnóstico."
                        OrderSide.SELL -> "No tienes participaciones disponibles para vender."
                        OrderSide.TRANSFER -> "Necesitas participaciones de algún fondo indexado disponible para traspasar."
                    },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Selector(if (side.value == OrderSide.TRANSFER) "Fondo de origen" else "Activo", options, asset.value) { asset.value = it; if (kinds[it] == AssetKind.INDEX_FUND) type.value = OrderType.MARKET }
            }
            if (side.value == OrderSide.TRANSFER) Selector("Fondo de destino", destinations, toAsset.value) { toAsset.value = it }

            val v = d.valuation
            if (side.value == OrderSide.BUY && v != null) Text("Disponible para operar: ${Fmt.eur(v.availableCash)}", style = MaterialTheme.typography.bodySmall)
            if (side.value != OrderSide.BUY) asset.value?.let { a -> d.sellable[a]?.let { Text("Disponibles: ${Fmt.units(it)} u.", style = MaterialTheme.typography.bodySmall) } }

            OutlinedTextField(
                value = amount.value, onValueChange = { amount.value = it },
                label = { Text(if (side.value == OrderSide.BUY) "Importe a invertir (€)" else "Unidades") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            if (side.value != OrderSide.TRANSFER) {
                Selector(
                    "Tipo de orden",
                    buildList {
                        add("A mercado" to OrderType.MARKET)
                        if (!isFund) {
                            add("Limitada" to OrderType.LIMIT)
                            if (side.value == OrderSide.SELL) add("Stop-loss" to OrderType.STOP)
                        }
                    },
                    type.value,
                ) { type.value = it }
                if (type.value != OrderType.MARKET) {
                    OutlinedTextField(
                        value = price.value, onValueChange = { price.value = it },
                        label = { Text(if (type.value == OrderType.STOP) "Precio de activación (€)" else "Precio límite (€)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    )
                    Selector("Caducidad", listOf("Hasta cancelar" to null, "7 días" to 7, "30 días" to 30, "90 días" to 90), expiryDays.value) { expiryDays.value = it }
                }
            }
            Text(
                when {
                    side.value == OrderSide.TRANSFER -> "El traspaso se ejecuta al valor liquidativo de ambos fondos y conserva el coste y la fecha de adquisición: no tributa hasta el reembolso."
                    isFund -> "Los fondos se ejecutan al valor liquidativo: el mismo día hábil si la orden se crea antes de las 14:00 (hora de Madrid); si no, el siguiente."
                    type.value == OrderType.MARKET -> "A mercado: se ejecuta en la siguiente sesión posterior a hoy, al precio de apertura."
                    type.value == OrderType.LIMIT -> "Limitada: solo se ejecuta si el precio supera el límite (rozarlo no basta)."
                    else -> "Stop-loss: se activa si el mínimo de la sesión llega al precio indicado."
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
            error.value?.let { Notice(it) }
            Button(
                onClick = {
                    val a = asset.value
                    val value = Fmt.parseMoney(amount.value)
                    val px = if (type.value == OrderType.MARKET) null else Fmt.parseMoney(price.value)
                    val expires = expiryDays.value?.let { c.container.today().plusDays(it.toLong()) }
                    when {
                        a == null -> error.value = "Elige un activo"
                        value == null || !value.isPositive -> error.value = "Escribe una cantidad válida"
                        type.value != OrderType.MARKET && (px == null || !px.isPositive) -> error.value = "Escribe un precio válido"
                        side.value == OrderSide.BUY -> {
                            if (!value.isWholeCents) error.value = "El importe admite como máximo 2 decimales"
                            else { error.value = null; c.placeBuy(a, value, type.value, px, expires); amount.value = "" }
                        }
                        side.value == OrderSide.SELL -> {
                            error.value = null; c.placeSell(a, Units.of(value.amount), type.value, px, expires); amount.value = ""
                        }
                        else -> {
                            val to = toAsset.value
                            if (to == null) error.value = "Elige el fondo de destino"
                            else { error.value = null; c.placeTransfer(a, to, Units.of(value.amount)); amount.value = "" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("Enviar orden") }
            Text(
                "Las órdenes se procesan cada día a las 00:00 (hora de Madrid) con los precios ya publicados, o al pulsar «Actualizar precios ahora» en Más › Diagnóstico.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Pendientes e historial

@Composable
private fun PendingSection(c: UiController, d: HomeData) {
    val pending = d.orders.filter { it.status == OrderStatus.ACCEPTED }
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (pending.isEmpty()) item { Text("No tienes órdenes pendientes.", modifier = Modifier.padding(vertical = 12.dp)) }
        items(pending) { o ->
            InfoCard {
                Text(o.title, style = MaterialTheme.typography.titleMedium)
                Text(o.detail, style = MaterialTheme.typography.bodyMedium)
                TimelineView(o.timeline)
                OutlinedButton(onClick = { c.cancelOrder(o.orderId) }, modifier = Modifier.padding(top = 8.dp)) { Text("Cancelar orden") }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun HistorySection(d: HomeData) {
    val showMovements = remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showMovements.value = false }) { Text(if (!showMovements.value) "● Órdenes" else "Órdenes") }
            TextButton(onClick = { showMovements.value = true }) { Text(if (showMovements.value) "● Movimientos" else "Movimientos") }
        }
        LazyColumn {
            if (!showMovements.value) {
                val done = d.orders.filter { it.status != OrderStatus.ACCEPTED }
                if (done.isEmpty()) item { Text("Todavía no hay órdenes ejecutadas, canceladas o caducadas.", modifier = Modifier.padding(vertical = 12.dp)) }
                items(done) { o ->
                    InfoCard {
                        Text(o.title, style = MaterialTheme.typography.titleMedium)
                        Text(o.detail, style = MaterialTheme.typography.bodySmall)
                        TimelineView(o.timeline)
                    }
                }
            } else {
                items(d.movements) { mv ->
                    InfoCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${mv.kind} · ${Fmt.date(mv.date)}", style = MaterialTheme.typography.titleSmall)
                            Text(mv.cashEffect?.let { Fmt.eur(it) } ?: "", color = signColor(mv.cashEffect?.signum?.let { if (it > 0) true else if (it < 0) false else null }))
                        }
                        Text(mv.description, style = MaterialTheme.typography.bodySmall)
                        Text("Efectivo tras el movimiento: ${Fmt.eur(mv.balance)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

// ------------------------------------------------------------------ Planes periódicos

@Composable
private fun PlansSection(c: UiController, d: HomeData) {
    val dialog = remember { mutableStateOf(false) }
    val names = AssetCatalog.DEFAULT.associate { it.id to it.name }
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        item {
            Text("Compras de un importe fijo o ventas de unidades fijas, a mercado, cada semana o cada mes.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { dialog.value = true }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Nuevo plan") }
        }
        items(d.plans) { p ->
            InfoCard {
                Text((if (p.side == OrderSide.BUY) "Compra" else "Venta") + " · " + (names[p.assetId] ?: p.assetId.value), style = MaterialTheme.typography.titleMedium)
                Text(
                    (p.amount?.let { Fmt.eur(it) } ?: (p.units?.let { Fmt.units(it) + " u." } ?: "")) + " · " +
                        (if (p.frequency == Frequency.MONTHLY) "cada mes, día ${p.dayOfMonth}" else "cada ${DayOfWeek.of(p.dayOfWeek).getDisplayName(TextStyle.FULL, Locale("es", "ES"))}") +
                        " · desde ${Fmt.date(p.startDate)}" + if (p.paused) " · EN PAUSA" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { c.setPlanPaused(p.id, !p.paused) }) { Text(if (p.paused) "Reanudar" else "Pausar") }
                    TextButton(onClick = { c.removePlan(p.id) }) { Text("Eliminar") }
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }
    if (dialog.value) NewPlanDialog(c, d, names) { dialog.value = false }
}

@Composable
private fun NewPlanDialog(c: UiController, d: HomeData, names: Map<AssetId, String>, dismiss: () -> Unit) {
    val side = remember { mutableStateOf(OrderSide.BUY) }
    val asset = remember { mutableStateOf<AssetId?>(null) }
    val amount = remember { mutableStateOf("") }
    val freq = remember { mutableStateOf(Frequency.MONTHLY) }
    val day = remember { mutableStateOf("1") }
    val weekday = remember { mutableStateOf(1) }
    val error = remember { mutableStateOf<String?>(null) }
    val buyable = d.markets.filter { it.hasData }.map { it.def.name to it.def.id }
    val sellable = d.sellable.filter { it.value.isPositive }.map { (names[it.key] ?: it.key.value) to it.key }
    val kinds = AssetCatalog.DEFAULT.associate { it.id to it.kind }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Nuevo plan periódico") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { side.value = OrderSide.BUY; asset.value = null }) { Text(if (side.value == OrderSide.BUY) "● Comprar" else "Comprar") }
                    OutlinedButton(onClick = { side.value = OrderSide.SELL; asset.value = null }) { Text(if (side.value == OrderSide.SELL) "● Vender" else "Vender") }
                }
                Selector("Activo", if (side.value == OrderSide.BUY) buyable else sellable, asset.value) { asset.value = it }
                OutlinedTextField(
                    value = amount.value, onValueChange = { amount.value = it },
                    label = { Text(if (side.value == OrderSide.BUY) "Importe (€)" else "Unidades") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Selector("Frecuencia", listOf("Cada mes" to Frequency.MONTHLY, "Cada semana" to Frequency.WEEKLY), freq.value) { freq.value = it }
                if (freq.value == Frequency.MONTHLY) {
                    OutlinedTextField(value = day.value, onValueChange = { day.value = it }, label = { Text("Día del mes (1-31)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                } else {
                    Selector("Día de la semana", (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale("es", "ES")) to it }, weekday.value) { weekday.value = it }
                }
                if (kinds[asset.value] == AssetKind.INDEX_FUND) Text("Se ejecuta a las 00:00 del día elegido; los fondos usan el valor liquidativo de ese día.", style = MaterialTheme.typography.bodySmall)
                error.value?.let { Notice(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = asset.value
                val v = Fmt.parseMoney(amount.value)
                val dd = day.value.trim().toIntOrNull()
                when {
                    a == null -> error.value = "Elige un activo"
                    v == null || !v.isPositive -> error.value = "Escribe una cantidad válida"
                    side.value == OrderSide.BUY && !v.isWholeCents -> error.value = "El importe admite como máximo 2 decimales"
                    freq.value == Frequency.MONTHLY && (dd == null || dd !in 1..31) -> error.value = "El día debe estar entre 1 y 31"
                    else -> {
                        try {
                            c.addPlan(
                                ScheduledPlan(
                                    id = "p" + System.currentTimeMillis(), side = side.value, assetId = a,
                                    amount = if (side.value == OrderSide.BUY) v else null,
                                    units = if (side.value == OrderSide.SELL) Units.of(v.amount) else null,
                                    frequency = freq.value, dayOfWeek = weekday.value, dayOfMonth = dd ?: 1, startDate = c.container.today(),
                                ),
                            )
                            dismiss()
                        } catch (e: Exception) {
                            error.value = e.message ?: "Plan no válido"
                        }
                    }
                }
            }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
}
