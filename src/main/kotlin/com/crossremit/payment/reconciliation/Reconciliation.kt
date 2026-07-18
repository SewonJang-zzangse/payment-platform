package com.crossremit.payment.reconciliation

import com.crossremit.payment.rails.RailAdapter
import com.crossremit.payment.transfer.Transfer
import com.crossremit.payment.transfer.TransferOrchestrator
import com.crossremit.payment.transfer.TransferState

/**
 * Reconciliation: comparing internal state (UNKNOWN transfers) against the rail's
 * settlement records to determine the truth. This is the only trustworthy way to
 * resolve an UNKNOWN payout.
 *
 * Match outcomes: confirmed sent / confirmed not sent / break (manual investigation).
 *
 * In the Temporal deployment, reconciliation delivers its verdict to the workflow
 * via a signal method, resuming the parked workflow.
 */
data class Break(val reason: String, val railReference: String, val detail: String = "")

class Reconciliation(
    private val orchestrator: TransferOrchestrator,
    private val rail: RailAdapter,
) {
    val breaks = mutableListOf<Break>()

    fun run(): List<Break> {
        val settlements = rail.fetchSettlement().associateBy { it.railReference }

        // 1) Match internal UNKNOWN transfers against settlement records
        for (t in orchestrator.transfers.values) {
            if (t.state != TransferState.PAYOUT_UNKNOWN) continue
            val s = settlements[t.railReference]
            when {
                s == null ->
                    // Past the rail's settlement SLA with no record -> confirmed not sent
                    orchestrator.onReconciledFailure(t)
                s.amount.amountMinor == t.target.amountMinor && s.amount.currency == t.target.currency ->
                    orchestrator.onReconciledSuccess(t)
                else ->
                    breaks += Break("AMOUNT_MISMATCH", t.railReference ?: "",
                        "internal=${t.target} settlement=${s.amount}")
            }
        }

        // 2) Settlement records that match no internal transfer -> break
        val knownRefs: Set<String?> = orchestrator.transfers.values.map { it.railReference }.toSet()
        for ((ref, s) in settlements) {
            if (ref !in knownRefs) breaks += Break("UNMATCHED_SETTLEMENT", ref, s.amount.toString())
        }
        return breaks
    }
}
