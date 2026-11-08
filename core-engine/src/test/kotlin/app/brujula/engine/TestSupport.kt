package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.LocalDate

internal fun d(text: String): LocalDate = LocalDate.parse(text)
internal fun m(text: String): Money = Money.of(text)
internal fun u(text: String): Units = Units.of(text)
internal val ASSET_A = AssetId("msci-world")
internal val ASSET_B = AssetId("sp500")
