package com.crossremit.payment.transfer

import com.crossremit.payment.money.Money
import com.crossremit.payment.rails.PayoutStatus
import java.util.UUID

/**
 * Pure (unit-testable) orchestrator — the twin of the Temporal workflow.
 *
 * This class lets the entire saga be tested without the Temporal SDK.
 * temporal/TransferWorkflowImpl.kt executes the same sequence through activity
 * stubs and, on UNKNOWN, parks in Workflow.await until reconciliation signals.
 *
 * Guarantees:
 *  - Happy path: pay-in -> (FX) -> reserve -> payout -> finalize
 *  - REJECTED: immediate compensation (full refund)
 *  - UNKNOWN: no retry, no compensation — funds stay parked in the settlement
 *    account until reconciliation determines the truth
 */
class TransferOrchestrator(
    private val activities: TransferActivitiesImpl,
) {
    val transfers: MutableMap<String, Transfer> = LinkedHashMap()

    fun createTransfer(user: String, source: Money, target: Money): Transfer {
        val t = Transfer(UUID.randomUUID().toString().take(8), user, source, target)
        transfers[t.id] = t
        activities.ensureAccounts(t)
        return t
    }

    fun execute(t: Transfer): Transfer {
        // 1) Risk gate — if screening fails, money never moves.
        t.state = TransferState.RISK_CHECK
        if (!activities.riskScreen(t.id, t.user, t.source)) {
            t.state = TransferState.REJECTED
            t.log("risk/AML rejected - no funds moved")
            return t
        }

        // 2) Pay-in
        activities.recordPayin(t.id, t.user, t.source)
        t.state = TransferState.FUNDS_IN
        t.log("pay-in ${t.source}")

        // 3) FX (only when currencies differ)
        if (t.requiresFx) {
            activities.recordFx(t.id, t.user, t.source, t.target)
            t.state = TransferState.CONVERTED
            t.log("FX ${t.source} -> ${t.target}")
        }

        // 4) Reserve payout: user -> settlement
        activities.reservePayout(t.id, t.user, t.target)

        // 5) Call the rail
        val result = activities.initiatePayout(t.id, t.target, t.user)
        t.railReference = result.railReference

        when (result.status) {
            PayoutStatus.ACCEPTED -> {
                activities.finalizePayout(t.id, t.target)
                t.state = TransferState.PAID_OUT
                t.log("payout accepted (${result.railReference})")
            }
            PayoutStatus.REJECTED -> {
                activities.compensate(t.id, t.source, t.target, t.requiresFx)
                t.state = TransferState.REFUNDED
                t.log("payout rejected (${result.detail}) -> compensated")
            }
            PayoutStatus.UNKNOWN -> {
                // The crux: timeout != failure. No retry (double payout risk),
                // no compensation (double loss risk). Funds stay isolated in settlement.
                t.state = TransferState.PAYOUT_UNKNOWN
                t.log("payout UNKNOWN (${result.detail}) -> awaiting reconciliation")
            }
        }
        return t
    }

    /** Reconciliation confirmed the money actually left. */
    fun onReconciledSuccess(t: Transfer) {
        if (t.state != TransferState.PAYOUT_UNKNOWN) return
        activities.finalizePayout(t.id, t.target)
        t.state = TransferState.PAID_OUT
        t.log("reconciliation: confirmed sent -> PAID_OUT")
    }

    /** Reconciliation confirmed the money never left — now compensation is safe. */
    fun onReconciledFailure(t: Transfer) {
        if (t.state != TransferState.PAYOUT_UNKNOWN) return
        activities.compensate(t.id, t.source, t.target, t.requiresFx)
        t.state = TransferState.REFUNDED
        t.log("reconciliation: confirmed not sent -> refunded")
    }
}
