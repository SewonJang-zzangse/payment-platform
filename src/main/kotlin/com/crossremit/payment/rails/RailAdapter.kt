package com.crossremit.payment.rails

import com.crossremit.payment.money.Money

/**
 * Payment rail adapter abstraction (anti-corruption layer).
 *
 * Key decision: [PayoutStatus.UNKNOWN] is a first-class return value.
 * A timeout is never collapsed into an exception — "we don't know whether the
 * money left" is a legitimate outcome that the caller must handle explicitly.
 * Adding a new rail = registering a new implementation; core code is untouched.
 */
interface RailAdapter {
    val railId: String
    val capabilities: Set<Capability>

    /** Must be idempotent per [PayoutCommand.idempotencyKey]: same key, same result. */
    fun initiatePayout(cmd: PayoutCommand): PayoutResult

    fun queryStatus(railReference: String): PayoutStatus

    /** Settlement records for reconciliation (in production: statement files / webhooks). */
    fun fetchSettlement(): List<SettlementRecord>
}

enum class PayoutStatus {
    ACCEPTED,   // the rail definitively accepted the payout
    REJECTED,   // the rail definitively rejected it (safe to compensate)
    UNKNOWN,    // timeout / indeterminate — automatic retry or compensation is forbidden
}

enum class Capability { SUPPORTS_CANCEL, INSTANT }

data class PayoutCommand(
    val idempotencyKey: String,
    val amount: Money,
    val beneficiary: String,
)

data class PayoutResult(
    val status: PayoutStatus,
    val railReference: String? = null,
    val detail: String = "",
)

data class SettlementRecord(
    val railReference: String,
    val amount: Money,
    val beneficiary: String,
)
