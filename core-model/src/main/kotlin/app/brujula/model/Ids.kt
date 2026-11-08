package app.brujula.model

/** Identificador de cuenta. */
@JvmInline
value class AccountId(val value: String) {
    init { require(value.isNotBlank()) { "El identificador de cuenta no puede estar vacío" } }
    override fun toString(): String = value
}

/** Identificador de activo (por ejemplo "msci-world"). */
@JvmInline
value class AssetId(val value: String) {
    init { require(value.isNotBlank()) { "El identificador de activo no puede estar vacío" } }
    override fun toString(): String = value
}
