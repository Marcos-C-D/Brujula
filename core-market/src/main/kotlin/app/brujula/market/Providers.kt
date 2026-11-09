package app.brujula.market

import app.brujula.engine.json.Json
import app.brujula.engine.json.JsonException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ProviderException(val provider: String, message: String) : RuntimeException("[$provider] $message")

data class HttpResponse(val code: Int, val body: String)

/** Acceso HTTP abstracto (en Android lo implementa HttpURLConnection; en pruebas, un doble). */
fun interface HttpFetcher {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** Proveedor de histórico diario de precios. */
interface HistoryProvider {
    val name: String
    fun fetchHistory(symbol: String, now: Instant): FetchedSeries
}

private fun BigDecimal.isUsable() = signum() > 0

private fun toBd(any: Any?): BigDecimal? = (any as? BigDecimal)

/**
 * Yahoo Finance (endpoint de gráficos, NO oficial: puede cambiar o limitar sin aviso y sus términos son de uso personal).
 * Por eso es solo un proveedor más tras la capa de datos, con alternativas.
 */
class YahooChartProvider(private val http: HttpFetcher) : HistoryProvider {
    override val name = "yahoo"

    fun url(symbol: String, now: Instant) =
        "https://query1.finance.yahoo.com/v8/finance/chart/${java.net.URLEncoder.encode(symbol, "UTF-8")}" +
            "?period1=0&period2=${now.epochSecond}&interval=1d&includeAdjustedClose=true"

    override fun fetchHistory(symbol: String, now: Instant): FetchedSeries {
        val resp = try {
            http.get(url(symbol, now), mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 15) Brujula/1.0", "Accept" to "application/json"))
        } catch (e: Exception) {
            throw ProviderException(name, "Sin conexión o error de red: ${e.message}")
        }
        if (resp.code != 200) throw ProviderException(name, "HTTP ${resp.code} para $symbol")
        return parse(symbol, resp.body, now)
    }

    fun parse(symbol: String, body: String, now: Instant): FetchedSeries {
        val root = try { Json.parse(body) as? Map<*, *> } catch (e: JsonException) { null }
            ?: throw ProviderException(name, "Respuesta no válida para $symbol")
        val chart = root["chart"] as? Map<*, *> ?: throw ProviderException(name, "Respuesta sin 'chart' para $symbol")
        (chart["error"] as? Map<*, *>)?.let { throw ProviderException(name, "$symbol: ${it["description"] ?: it["code"] ?: "error"}") }
        val result = (chart["result"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw ProviderException(name, "Sin resultados para $symbol")
        val meta = result["meta"] as? Map<*, *> ?: throw ProviderException(name, "Sin metadatos para $symbol")
        val currency = meta["currency"] as? String ?: throw ProviderException(name, "Sin divisa para $symbol")
        val offset = (toBd(meta["gmtoffset"])?.toLong() ?: 0L)
        val ts = result["timestamp"] as? List<*> ?: throw ProviderException(name, "Sin fechas para $symbol")
        val ind = result["indicators"] as? Map<*, *> ?: throw ProviderException(name, "Sin indicadores para $symbol")
        val quote = (ind["quote"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: throw ProviderException(name, "Sin cotizaciones para $symbol")
        val closes = quote["close"] as? List<*> ?: throw ProviderException(name, "Sin cierres para $symbol")
        val opens = quote["open"] as? List<*>
        val highs = quote["high"] as? List<*>
        val lows = quote["low"] as? List<*>
        val adj = ((ind["adjclose"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("adjclose") as? List<*>
        if (closes.size != ts.size) throw ProviderException(name, "Datos inconsistentes para $symbol")

        val points = ArrayList<PricePoint>()
        var discarded = 0
        for (i in ts.indices) {
            val epoch = toBd(ts[i])?.toLong()
            if (epoch == null) { discarded++; continue }
            val close = toBd(closes[i])
            if (close == null) continue // día sin cotización: es normal que venga null
            if (!close.isUsable()) { discarded++; continue }
            val date = Instant.ofEpochSecond(epoch + offset).atZone(ZoneOffset.UTC).toLocalDate()
            val adjClose = adj?.getOrNull(i)?.let { toBd(it) }?.takeIf { it.isUsable() }
            points += PricePoint(
                date, close,
                opens?.getOrNull(i)?.let { toBd(it) }?.takeIf { it.isUsable() },
                highs?.getOrNull(i)?.let { toBd(it) }?.takeIf { it.isUsable() },
                lows?.getOrNull(i)?.let { toBd(it) }?.takeIf { it.isUsable() },
                adjClose,
            )
        }
        if (points.isEmpty()) throw ProviderException(name, "$symbol no devolvió ningún precio válido")
        val warnings = if (discarded > 0) listOf("$discarded puntos descartados por datos no válidos") else emptyList()
        return FetchedSeries(symbol, currency, name, points, warnings, now)
    }
}

/** CoinGecko (API pública gratuita; el histórico gratuito se limita a los últimos 365 días). */
class CoinGeckoProvider(private val http: HttpFetcher) : HistoryProvider {
    override val name = "coingecko"

    fun url(id: String) =
        "https://api.coingecko.com/api/v3/coins/${java.net.URLEncoder.encode(id, "UTF-8")}/market_chart?vs_currency=eur&days=365&interval=daily"

    override fun fetchHistory(symbol: String, now: Instant): FetchedSeries {
        val resp = try {
            http.get(url(symbol), mapOf("Accept" to "application/json", "User-Agent" to "Brujula/1.0"))
        } catch (e: Exception) {
            throw ProviderException(name, "Sin conexión o error de red: ${e.message}")
        }
        if (resp.code == 429) throw ProviderException(name, "Límite de peticiones alcanzado (HTTP 429)")
        if (resp.code != 200) throw ProviderException(name, "HTTP ${resp.code} para $symbol")
        return parse(symbol, resp.body, now)
    }

    fun parse(symbol: String, body: String, now: Instant): FetchedSeries {
        val root = try { Json.parse(body) as? Map<*, *> } catch (e: JsonException) { null }
            ?: throw ProviderException(name, "Respuesta no válida para $symbol")
        val prices = root["prices"] as? List<*> ?: throw ProviderException(name, "Sin precios para $symbol")
        val byDate = LinkedHashMap<LocalDate, BigDecimal>()
        var discarded = 0
        for (row in prices) {
            val pair = row as? List<*>
            val ms = toBd(pair?.getOrNull(0))?.toLong()
            val price = toBd(pair?.getOrNull(1))
            if (ms == null || price == null || !price.isUsable()) { discarded++; continue }
            byDate[Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()] = price // gana el último del día
        }
        if (byDate.isEmpty()) throw ProviderException(name, "$symbol no devolvió ningún precio válido")
        val points = byDate.map { (d, p) -> PricePoint(d, p) }
        val warnings = buildList {
            if (discarded > 0) add("$discarded puntos descartados por datos no válidos")
            add("Histórico limitado a 365 días por la API gratuita")
        }
        return FetchedSeries(symbol, "EUR", name, points, warnings, now)
    }
}

/** Tipos de cambio diarios del BCE (servicio de datos estadísticos, gratuito). */
class EcbFxProvider(private val http: HttpFetcher) {
    val name = "bce"

    fun url(currency: String) =
        "https://data-api.ecb.europa.eu/service/data/EXR/D.$currency.EUR.SP00.A?format=csvdata&startPeriod=2000-01-01"

    fun fetch(currency: String): FxSeries {
        val resp = try {
            http.get(url(currency), mapOf("Accept" to "text/csv", "User-Agent" to "Brujula/1.0"))
        } catch (e: Exception) {
            throw ProviderException(name, "Sin conexión o error de red: ${e.message}")
        }
        if (resp.code != 200) throw ProviderException(name, "HTTP ${resp.code} para $currency")
        return parse(currency, resp.body)
    }

    fun parse(currency: String, csv: String): FxSeries {
        val lines = csv.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw ProviderException(name, "Respuesta vacía para $currency")
        val header = splitCsv(lines.first())
        val iDate = header.indexOf("TIME_PERIOD")
        val iVal = header.indexOf("OBS_VALUE")
        if (iDate < 0 || iVal < 0) throw ProviderException(name, "Formato CSV inesperado para $currency")
        val rates = LinkedHashMap<LocalDate, BigDecimal>()
        for (line in lines.drop(1)) {
            val f = splitCsv(line)
            val d = f.getOrNull(iDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val v = f.getOrNull(iVal)?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: continue
            if (v.signum() > 0) rates[d] = v
        }
        if (rates.isEmpty()) throw ProviderException(name, "Sin tipos de cambio para $currency")
        return FxSeries(currency, rates)
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }
}
