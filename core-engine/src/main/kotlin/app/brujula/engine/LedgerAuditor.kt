package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units

/**
 * Auditoría independiente: recalcula con sumas simples y comprueba las identidades que deben
 * cumplirse siempre. Una lista vacía significa que todo cuadra al céntimo.
 */
object LedgerAuditor {

    fun audit(records: List<LedgerRecord>): List<String> {
        val problems = ArrayList<String>()

        records.forEachIndexed { i, r ->
            if (r.seq != i + 1L) problems += "Secuencia incorrecta en la posición ${i + 1}: ${r.seq}"
        }
        records.zipWithNext().forEach { (a, b) ->
            if (b.event.date.isBefore(a.event.date)) problems += "Fechas desordenadas entre #${a.seq} y #${b.seq}"
        }

        val state = try {
            Ledger.replay(app.brujula.model.AccountId("auditoria"), records.map { it.event }).state
        } catch (e: LedgerException) {
            return problems + "El libro no se puede reproducir: ${e.message}"
        }

        var cash = Money.ZERO
        var contributions = Money.ZERO
        var adjustments = Money.ZERO
        val bought = HashMap<AssetId, Units>()
        val sold = HashMap<AssetId, Units>()
        val purchaseCost = HashMap<AssetId, Money>()

        for (r in records) {
            when (val e = r.event) {
                is OpeningBalance -> { cash += e.amount; contributions += e.amount }
                is MonthlyIncome -> { cash += e.amount; contributions += e.amount }
                is Purchase -> {
                    cash -= e.totalCost
                    bought.merge(e.assetId, e.units) { a, b -> a + b }
                    purchaseCost.merge(e.assetId, e.totalCost) { a, b -> a + b }
                }
                is Sale -> {
                    cash += e.netProceeds
                    sold.merge(e.assetId, e.units) { a, b -> a + b }
                }
                is CashAdjustment -> { cash += e.amount; adjustments += e.amount }
            }
        }

        if (cash != state.cash) problems += "Efectivo: suma independiente $cash ≠ estado ${state.cash}"
        if (contributions != state.contributions) problems += "Aportaciones: $contributions ≠ ${state.contributions}"
        if (adjustments != state.adjustmentsTotal) problems += "Ajustes: $adjustments ≠ ${state.adjustmentsTotal}"

        for (asset in bought.keys) {
            val expectedUnits = bought.getValue(asset) - (sold[asset] ?: Units.ZERO)
            val held = state.unitsHeld(asset)
            if (expectedUnits != held) problems += "Unidades de $asset: compradas-vendidas $expectedUnits ≠ en cartera $held"

            val remainingCost = state.position(asset)?.costBasis ?: Money.ZERO
            val consumedCost = state.realizedSales.filter { it.assetId == asset }
                .fold(Money.ZERO) { acc, s -> acc + s.cost }
            if (purchaseCost.getValue(asset) != remainingCost + consumedCost) {
                problems += "Coste de $asset: comprado ${purchaseCost.getValue(asset)} ≠ en cartera $remainingCost + consumido $consumedCost"
            }
        }

        for ((asset, lots) in state.lotsByAsset) {
            for (lot in lots) {
                if (lot.unitsRemaining.isNegative || lot.costRemaining.isNegative) problems += "Lote #${lot.acquisitionSeq} de $asset con valores negativos"
                if (!lot.costRemaining.isWholeCents) problems += "Lote #${lot.acquisitionSeq} de $asset con coste fuera de céntimos"
            }
        }

        // Identidad global: efectivo + coste en cartera = aportaciones + beneficio realizado + ajustes
        val left = state.cash + state.investedCost
        val right = state.contributions + state.realizedGain + state.adjustmentsTotal
        if (left != right) problems += "Identidad global rota: efectivo+coste $left ≠ aportaciones+realizado+ajustes $right"

        return problems
    }
}
