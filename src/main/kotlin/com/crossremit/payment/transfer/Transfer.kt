package com.crossremit.payment.transfer

import com.crossremit.payment.money.Money

/** Transfer lifecycle states. */
enum class TransferState {
    QUOTED, RISK_CHECK, REJECTED, FUNDS_IN, CONVERTED,
    PAID_OUT, PAYOUT_UNKNOWN, REFUNDED,
}

/** Transfer aggregate. Under Temporal, the workflow holds this state durably. */
data class Transfer(
    val id: String,
    val user: String,
    val source: Money,
    val target: Money,
    var state: TransferState = TransferState.QUOTED,
    var railReference: String? = null,
    val history: MutableList<String> = mutableListOf(),
) {
    val requiresFx: Boolean get() = source.currency != target.currency
    fun log(msg: String) { history += "[${state.name}] $msg" }
}

/** Account naming conventions. */
object Accounts {
    fun user(user: String, ccy: String) = "USER:$user:$ccy"
    fun nostroIn(ccy: String) = "NOSTRO_IN:$ccy"
    fun nostroOut(ccy: String) = "NOSTRO_OUT:$ccy"
    fun settlement(ccy: String) = "SETTLEMENT:$ccy"
    fun fxPosition(ccy: String) = "FX_POSITION:$ccy"
}
