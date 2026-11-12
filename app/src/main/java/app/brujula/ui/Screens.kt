package app.brujula.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.HomeData
import app.brujula.app.AssetDetail
import app.brujula.app.Fmt
import app.brujula.app.MarketRow
import app.brujula.market.ReturnPeriod
import app.brujula.market.ReturnResult
import java.time.temporal.ChronoUnit

// ------------------------------------------------------------------ Inicio

@Composable
fun HomeScreen(d: HomeData, goAccounts: () -> Unit) {
    val v = d.valuation
    if (v == null) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoCard {
                Text("Aún no tienes ninguna cuenta", style = MaterialTheme.typography.titleMedium)
                Text("Crea una cuenta con su saldo inicial y su ingreso mensual para empezar a simular.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = goAccounts, modifier = Modifier.padding(top = 12.dp)) { Text("Crear cuenta") }
            }
        }
        return
    }
    val name = d.accounts.firstOrNull { it.id == d.selectedId }?.name ?: ""
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        item {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text("Patrimonio total", style = MaterialTheme.typography.bodySmall)
            Text(Fmt.eur(v.netWorth), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (!v.complete) Notice("Falta el precio de ${v.missingPrices.size} activo(s) en cartera: su valor no está incluido. Actualiza los precios en Más › Diagnóstico.")
            if (v.cash.isNegative) Notice("El efectivo es negativo (por ejemplo tras pagar impuestos). No podrás comprar hasta que vuelva a haber saldo.")
        }
        item {
            InfoCard {
                KeyValue("Efectivo", Fmt.eur(v.cash), if (v.cash.isNegative) Rojo else androidx.compose.ui.graphics.Color.Unspecified)
                KeyValue("Disponible para operar", Fmt.eur(v.availableCash))
                if (v.reserved.isPositive) KeyValue("Reservado en órdenes", Fmt.eur(v.reserved))
                if (v.unsettled.isPositive) KeyValue("Pendiente de liquidar", Fmt.eur(v.unsettled))
                KeyValue("Valor de las inversiones", Fmt.eur(v.investedValue))
                KeyValue("Beneficio / pérdida", Fmt.eur(v.gain) + "  (" + Fmt.pctOrDash(v.gainPct) + ")", signColor(v.gain.signum.let { if (it > 0) true else if (it < 0) false else null }))
                KeyValue("Aportado (saldo inicial + ingresos)", Fmt.eur(v.contributions))
                KeyValue("Rentabilidad total", Fmt.pctOrDash(v.totalReturnPct), signColor(v.totalReturnPct.sign()))
            }
        }
        item {
            SectionTitle("Evolución del patrimonio")
            LineChart(d.history.map { it.netWorth?.amount?.toDouble() })
            if (d.history.size >= 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.date(d.history.first().date), style = MaterialTheme.typography.bodySmall)
                    Text(Fmt.date(d.history.last().date), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (v.allocationByAsset.isNotEmpty()) {
            item { SectionTitle("Distribución por activos"); AllocationBars(v.allocationByAsset) }
            item { SectionTitle("Distribución por categorías"); AllocationBars(v.allocationByCategory) }
        }
        item {
            Text(
                "Precios actualizados: ${formatInstant(d.lastMarketUpdate)}. Simulación educativa; los precios son de un ETF proxy y pueden tener retraso.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Cartera

@Composable
fun PortfolioScreen(d: HomeData) {
    val v = d.valuation
    if (v == null || v.rows.isEmpty()) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoCard {
                Text("Cartera vacía", style = MaterialTheme.typography.titleMedium)
                Text("Cuando compres un activo aparecerá aquí con su precio medio, su valor y su beneficio o pérdida.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        items(v.rows) { r ->
            InfoCard {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text(r.category, style = MaterialTheme.typography.bodySmall)
                KeyValue("Participaciones", Fmt.units(r.units))
                KeyValue("Precio medio de compra", Fmt.price(r.averageCost))
                KeyValue("Coste fiscal", Fmt.eur(r.costBasis))
                KeyValue("Precio actual", r.price?.let { Fmt.price(it) + (r.priceDate?.let { dt -> " (${Fmt.date(dt)})" } ?: "") } ?: "—")
                KeyValue("Valor actual", Fmt.eurOrDash(r.value))
                KeyValue("Beneficio / pérdida", (r.gain?.let { Fmt.eur(it) } ?: "—") + "  (" + Fmt.pctOrDash(r.gainPct) + ")", signColor(r.gainPct.sign()))
                if (r.price == null) Notice("Sin precio: actualiza los datos de mercado.")
                else if (r.stale) Notice("El precio no está al día: puede haber un retraso del proveedor.")
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

// ------------------------------------------------------------------ Mercados

@Composable
fun MarketsScreen(d: HomeData, open: (app.brujula.model.AssetId) -> Unit) {
    val query = remember { mutableStateOf("") }
    val category = remember { mutableStateOf<String?>(null) }
    val categories = d.markets.map { it.def.category }.distinct()
    val shown = d.markets.filter {
        (category.value == null || it.def.category == category.value) &&
            (query.value.isBlank() || it.def.name.contains(query.value.trim(), ignoreCase = true))
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query.value, onValueChange = { query.value = it }, label = { Text("Buscar activo") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { category.value = null }) { Text(if (category.value == null) "● Todos" else "Todos") }
            categories.forEach { c -> TextButton(onClick = { category.value = c }) { Text(if (category.value == c) "● $c" else c, maxLines = 1) } }
        }
        LazyColumn {
            items(shown) { row -> MarketCard(row) { open(row.def.id) } }
            item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun MarketCard(r: MarketRow, onClick: () -> Unit) {
    InfoCard(onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(r.def.name, style = MaterialTheme.typography.titleMedium)
                Text(r.def.category, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(r.price?.let { Fmt.price(it) } ?: "—", fontWeight = FontWeight.Bold)
                Text(Fmt.pctOrDash(r.dayChangePct), color = signColor(r.dayChangePct.sign()), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (r.hasData) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("YTD " + returnText(r.ytd), style = MaterialTheme.typography.bodySmall)
                Text("1 año " + returnText(r.year1), style = MaterialTheme.typography.bodySmall)
                Text("Riesgo " + (r.riskClass?.let { "$it/7" } ?: "—"), style = MaterialTheme.typography.bodySmall)
            }
            if (r.stale) Notice("Precio no actualizado")
        } else {
            Text(r.note ?: "Sin datos", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun returnText(r: ReturnResult?): String = when (r) {
    is ReturnResult.Value -> Fmt.pct(r.percent, signed = true)
    is ReturnResult.Insufficient -> "s/d"
    null -> "—"
}

// ------------------------------------------------------------------ Ficha de activo

private enum class Range(val label: String, val days: Long?) {
    M1("1M", 31), M6("6M", 183), Y1("1A", 366), Y5("5A", 1827), ALL("Todo", null),
}

@Composable
fun AssetDetailScreen(detail: AssetDetail?) {
    val range = remember { mutableStateOf(Range.Y1) }
    if (detail == null) {
        Text("Cargando…", modifier = Modifier.padding(16.dp))
        return
    }
    val last = detail.series.last
    val points = detail.series.points.let { all ->
        val days = range.value.days
        if (days == null) all else all.filter { ChronoUnit.DAYS.between(it.date, last.date) <= days }
    }
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        item {
            Text(detail.def.name, style = MaterialTheme.typography.headlineSmall)
            Text(detail.def.category + " · " + detail.def.referenceIndex, style = MaterialTheme.typography.bodySmall)
            Text(Fmt.price(app.brujula.model.Money.of(last.close)), fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text("Cierre del ${Fmt.date(last.date)} · divisa original ${detail.originalCurrency}", style = MaterialTheme.typography.bodySmall)
            if (detail.status.isStale) Notice("El precio no está al día (esperado: ${Fmt.date(detail.status.expectedDate)}).")
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Range.values().forEach { r -> TextButton(onClick = { range.value = r }) { Text(if (range.value == r) "● ${r.label}" else r.label) } }
            }
            LineChart(points.map { it.close.toDouble() })
            if (points.size >= 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.date(points.first().date), style = MaterialTheme.typography.bodySmall)
                    Text(Fmt.date(points.last().date), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SectionTitle("Rentabilidad histórica")
            InfoCard {
                ReturnPeriod.values().forEach { p ->
                    val r = detail.returns[p]
                    when (r) {
                        is ReturnResult.Value -> KeyValue(p.label, Fmt.pct(r.percent, true), signColor(r.percent.sign()))
                        is ReturnResult.Insufficient -> KeyValue(p.label, "Sin datos suficientes")
                        null -> KeyValue(p.label, "—")
                    }
                }
            }
            Text("Calculada con datos reales entre dos cierres. Nunca se rellenan huecos con datos inventados.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionTitle("Riesgo")
            InfoCard { Text(detail.riskText, style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            SectionTitle("De dónde salen los datos")
            InfoCard {
                Text(detail.def.proxyNote, style = MaterialTheme.typography.bodyMedium)
                KeyValue("Símbolo del proxy", detail.symbol)
                KeyValue("Proveedor", detail.provider)
                KeyValue("Última actualización", formatInstant(detail.fetchedAt))
                Text(detail.conversionNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                detail.lastError?.let { Notice("Último intento de actualización fallido: $it") }
            }
            Text("", modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
