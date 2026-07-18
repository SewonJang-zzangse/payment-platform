package com.crossremit.payment.transfer

import com.crossremit.payment.ledger.Ledger
import com.crossremit.payment.ledger.Posting
import com.crossremit.payment.money.Money
import com.crossremit.payment.rails.PayoutCommand
import com.crossremit.payment.rails.PayoutResult
import com.crossremit.payment.rails.RailAdapter

/**
 * The side-effecting steps of a transfer. Every method is idempotent through a
 * deterministic ledger reference ("{transferId}:{step}") — the command-boundary
 * and ledger-boundary layers of the idempotency strategy.
 *
 * In the Temporal model this interface is the activity contract; the workflow
 * invokes these steps in order and owns the state machine.
 * (See transfer/temporal/TransferWorkflow.kt for the annotated version.)
 */
interface TransferActivities {
    fun riskScreen(transferId: String, user: String, amount: Money): Boolean
    fun recordPayin(transferId: String, user: String, source: Money)
    fun recordFx(transferId: String, user: String, source: Money, target: Money)
    fun reservePayout(transferId: String, user: String, target: Money)
    fun initiatePayout(transferId: String, target: Money, beneficiary: String): PayoutResult
    fun finalizePayout(transferId: String, target: Money)
    fun compensate(transferId: String, source: Money, target: Money, converted: Boolean)
}

/** Delegates to the ledger and the rail. The Temporal activity impl wraps this class as-is. */
class TransferActivitiesImpl(
    private val ledger: Ledger,
    private val rail: RailAdapter,
    private val riskPolicy: (user: String, amount: Money) -> Boolean = { _, _ -> true },
) : TransferActivities {

    override fun riskScreen(transferId: String, user: String, amount: Money): Boolean =
        riskPolicy(user, amount)

    override fun recordPayin(transferId: String, user: String, source: Money) {
        ledger.postIdempotent(
            "$transferId:payin", "PAYIN",
            listOf(
                Posting(Accounts.nostroIn(source.currency), source),
                Posting(Accounts.user(user, source.currency), -source),
            ),
        )
    }

    override fun recordFx(transferId: String, user: String, source: Money, target: Money) {
        ledger.postIdempotent(
            "$transferId:fx", "FX_CONVERT",
            listOf(
                Posting(Accounts.user(user, source.currency), source),
                Posting(Accounts.fxPosition(source.currency), -source),
                Posting(Accounts.fxPosition(target.currency), target),
                Posting(Accounts.user(user, target.currency), -target),
            ),
        )
    }

    override fun reservePayout(transferId: String, user: String, target: Money) {
        ledger.postIdempotent(
            "$transferId:payout-reserve", "PAYOUT_RESERVE",
            listOf(
                Posting(Accounts.user(user, target.currency), target),
                Posting(Accounts.settlement(target.currency), -target),
            ),
        )
    }

    override fun initiatePayout(transferId: String, target: Money, beneficiary: String): PayoutResult =
        rail.initiatePayout(PayoutCommand("$transferId:payout", target, beneficiary))

    override fun finalizePayout(transferId: String, target: Money) {
        ledger.postIdempotent(
            "$transferId:payout-finalize", "PAYOUT_FINALIZE",
            listOf(
                Posting(Accounts.settlement(target.currency), target),
                Posting(Accounts.nostroOut(target.currency), -target),
            ),
        )
    }

    override fun compensate(transferId: String, source: Money, target: Money, converted: Boolean) {
        // Compensation is reversal, not deletion. The append-only ledger never erases anything.
        ledger.reverse("$transferId:payout-reserve", "$transferId:rev-payout-reserve")
        if (converted) ledger.reverse("$transferId:fx", "$transferId:rev-fx")
        ledger.reverse("$transferId:payin", "$transferId:rev-payin")
    }

    fun ensureAccounts(t: Transfer) {
        for (ccy in setOf(t.source.currency, t.target.currency)) {
            ledger.openAccount(Accounts.user(t.user, ccy), "USER_BALANCE", ccy)
            ledger.openAccount(Accounts.nostroIn(ccy), "NOSTRO", ccy)
            ledger.openAccount(Accounts.nostroOut(ccy), "NOSTRO", ccy)
            ledger.openAccount(Accounts.settlement(ccy), "SETTLEMENT", ccy)
            ledger.openAccount(Accounts.fxPosition(ccy), "FX_POSITION", ccy)
        }
    }
}
