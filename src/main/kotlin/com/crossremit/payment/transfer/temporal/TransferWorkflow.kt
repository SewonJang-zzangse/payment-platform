package com.crossremit.payment.transfer.temporal

import com.crossremit.payment.money.Money
import com.crossremit.payment.rails.PayoutResult
import com.crossremit.payment.transfer.TransferState
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Temporal workflow/activity contracts (orchestration saga).
 *
 * Why Temporal: workflow state is persisted durably, so a crashed process resumes
 * exactly where it left off. In particular, parking on PAYOUT_UNKNOWN with
 * Workflow.await and resuming on a reconciliation signal (@SignalMethod) fits this
 * domain precisely.
 *
 * Note: this file requires the io.temporal SDK dependency (see build.gradle.kts).
 */
@WorkflowInterface
interface TransferWorkflow {
    @WorkflowMethod
    fun execute(cmd: TransferCommand): TransferOutcome

    /** Delivers the reconciliation verdict; a parked workflow resumes on this signal. */
    @SignalMethod
    fun reconcile(result: ReconResult)

    /** Live state query — ops/support can always ask "where is this transfer?". */
    @QueryMethod
    fun currentState(): TransferState
}

@ActivityInterface
interface TransferActivitiesGateway {
    @ActivityMethod fun riskScreen(transferId: String, user: String, amount: Money): Boolean
    @ActivityMethod fun recordPayin(transferId: String, user: String, source: Money)
    @ActivityMethod fun recordFx(transferId: String, user: String, source: Money, target: Money)
    @ActivityMethod fun reservePayout(transferId: String, user: String, target: Money)
    @ActivityMethod fun initiatePayout(transferId: String, target: Money, beneficiary: String): PayoutResult
    @ActivityMethod fun finalizePayout(transferId: String, target: Money)
    @ActivityMethod fun compensate(transferId: String, source: Money, target: Money, converted: Boolean)
}

data class TransferCommand(
    val transferId: String,
    val user: String,
    val source: Money,
    val target: Money,
)

data class TransferOutcome(val transferId: String, val state: TransferState)

enum class ReconOutcome { CONFIRMED_SENT, CONFIRMED_NOT_SENT }
data class ReconResult(val outcome: ReconOutcome)
