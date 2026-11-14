package app.brujula.market

import app.brujula.model.AssetId

/** Clase de instrumento: no se tratan igual (valor liquidativo, cotización de ETF, precio de Bitcoin). */
enum class AssetKind { INDEX_FUND, ETF, CRYPTO }

enum class ProviderId { YAHOO, COINGECKO }

/** Dónde se obtiene la serie: proveedor + símbolo. Se prueban en orden hasta que uno responde. */
data class SourceSpec(val provider: ProviderId, val symbol: String)

/**
 * Definición de un activo. No lleva marcas de gestoras: muestra el índice o sector. La serie real procede de un
 * proxy (un ETF o criptoactivo que lo replica), cuyos símbolos y diferencias se documentan.
 */
data class AssetDefinition(
    val id: AssetId,
    val name: String,
    val category: String,
    val kind: AssetKind,
    val referenceIndex: String,
    val sources: List<SourceSpec>,
    /** Calendario de negociación del proxy (null = 24/7). */
    val marketId: String?,
    /** true si el producto reparte dividendos (distribución); false = acumulación (no tributa hasta vender). */
    val distributes: Boolean = false,
) {
    val proxyNote: String
        get() = when (kind) {
            AssetKind.INDEX_FUND ->
                "No se usa el índice oficial ni el valor liquidativo de un fondo: la serie es la de un ETF que replica $referenceIndex " +
                    "(proxy). Las diferencias (comisiones, réplica, divisa, hora de cierre) hacen que no coincida exactamente."
            AssetKind.ETF -> "Serie de cotización de un ETF representativo de $referenceIndex."
            AssetKind.CRYPTO -> "Precio de mercado de Bitcoin en euros, 24 horas al día, 7 días a la semana."
        }
}

/**
 * Catálogo inicial. Los símbolos de proxy son candidatos que NO se han podido verificar desde el entorno de
 * desarrollo: la pantalla de diagnóstico indica cuáles responden y cuáles hay que sustituir.
 */
object AssetCatalog {
    private fun y(vararg s: String) = s.map { SourceSpec(ProviderId.YAHOO, it) }

    val DEFAULT: List<AssetDefinition> = listOf(
        AssetDefinition(AssetId("msci-world"), "MSCI World", "Fondos indexados", AssetKind.INDEX_FUND, "el índice MSCI World", y("IWDA.AS", "EUNL.DE"), "EURONEXT"),
        AssetDefinition(AssetId("nasdaq-100"), "Nasdaq 100", "Fondos indexados", AssetKind.INDEX_FUND, "el índice Nasdaq 100", y("SXRV.DE", "EQQQ.DE"), "XETRA"),
        AssetDefinition(AssetId("sp500"), "S&P 500", "Fondos indexados", AssetKind.INDEX_FUND, "el índice S&P 500", y("SXR8.DE", "VUAA.DE"), "XETRA"),
        AssetDefinition(AssetId("msci-em"), "MSCI Emerging Markets IMI", "Fondos indexados", AssetKind.INDEX_FUND, "el índice MSCI Emerging Markets IMI", y("IS3N.DE", "EMIM.AS"), "XETRA"),
        AssetDefinition(AssetId("msci-europe"), "MSCI Europe", "Fondos indexados", AssetKind.INDEX_FUND, "el índice MSCI Europe", y("IMEU.AS", "MEUD.PA"), "EURONEXT"),
        AssetDefinition(AssetId("msci-acwi"), "MSCI ACWI", "Fondos indexados", AssetKind.INDEX_FUND, "el índice MSCI ACWI", y("IUSQ.DE", "SSAC.L"), "XETRA"),
        AssetDefinition(AssetId("msci-world-small-cap"), "MSCI World Small Cap", "Fondos indexados", AssetKind.INDEX_FUND, "el índice MSCI World Small Cap", y("IUSN.DE", "WSML.L"), "XETRA"),
        AssetDefinition(AssetId("msci-acwi-imi"), "MSCI ACWI IMI", "Fondos indexados", AssetKind.INDEX_FUND, "el índice MSCI ACWI IMI", y("SPYY.DE", "SPYY.L"), "XETRA"),
        AssetDefinition(
            AssetId("monetario-estr"), "Fondo monetario €STR (EUR, acumulación)", "Fondos monetarios", AssetKind.INDEX_FUND,
            "el tipo de interés a un día del euro (€STR)", y("XEON.DE", "CSH2.PA"), "XETRA",
        ),
        AssetDefinition(
            AssetId("lifestrategy-60"), "Vanguard LifeStrategy 60% Equity (EUR)", "Fondos mixtos", AssetKind.INDEX_FUND,
            "una cartera global de 60 % renta variable y 40 % renta fija", y("V60A.DE", "VNGA60.MI"), "XETRA",
        ),

        AssetDefinition(AssetId("etf-semiconductores"), "Semiconductores", "ETF", AssetKind.ETF, "el sector de semiconductores", y("VVSM.DE"), "XETRA"),
        AssetDefinition(AssetId("etf-defensa"), "Defensa", "ETF", AssetKind.ETF, "el sector de defensa", y("DFEN.DE"), "XETRA"),
        AssetDefinition(AssetId("etf-materias-primas"), "Materias primas", "ETF", AssetKind.ETF, "las materias primas diversificadas", y("ICOM.L"), "LSE"),
        AssetDefinition(AssetId("etf-oro"), "Oro", "ETF", AssetKind.ETF, "el precio del oro", y("4GLD.DE", "SGLD.L"), "XETRA"),
        AssetDefinition(AssetId("etf-energia"), "Energía", "ETF", AssetKind.ETF, "el sector energético", y("QDVF.DE", "IUES.L"), "XETRA"),
        AssetDefinition(AssetId("etf-tecnologia"), "Tecnología", "ETF", AssetKind.ETF, "el sector tecnológico", y("QDVE.DE", "IUIT.L"), "XETRA"),
        AssetDefinition(AssetId("etf-emergentes"), "Mercados emergentes (ETF)", "ETF", AssetKind.ETF, "los mercados emergentes", y("EMIM.AS", "EIMI.L"), "EURONEXT"),
        AssetDefinition(AssetId("etf-bonos"), "Bonos", "ETF", AssetKind.ETF, "la renta fija agregada", y("IEAG.AS", "EUNA.DE"), "EURONEXT"),
        AssetDefinition(AssetId("etf-dividendos"), "Dividendos (reparte en efectivo)", "ETF", AssetKind.ETF, "las acciones con altos dividendos (distribución: cobras los dividendos y no se reinvierten)", y("EXSH.DE", "VHYL.AS"), "XETRA", distributes = true),

        AssetDefinition(
            AssetId("bitcoin"), "Bitcoin", "Criptomonedas", AssetKind.CRYPTO, "Bitcoin",
            listOf(SourceSpec(ProviderId.YAHOO, "BTC-EUR"), SourceSpec(ProviderId.COINGECKO, "bitcoin")), null,
        ),
    )

    fun byId(id: AssetId): AssetDefinition? = DEFAULT.firstOrNull { it.id == id }
}
