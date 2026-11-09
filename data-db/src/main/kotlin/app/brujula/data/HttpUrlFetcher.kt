package app.brujula.data

import app.brujula.market.HttpFetcher
import app.brujula.market.HttpResponse
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP sin dependencias externas (HttpURLConnection) con tiempos de espera razonables. */
class HttpUrlFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
