package com.crossremit.payment.transfer.temporal

import com.crossremit.payment.rails.PayoutStatus
import com.crossremit.payment.transfer.TransferState
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Workflow implementation. Follows the same saga sequence as the pure
 * TransferOrchestrator, but invokes steps through activity stubs and waits for
 * the reconciliation signal on UNKNOWN.
 *
 * Key decisions:
 *  - Most activities auto-retry (RetryOptions) — they are idempotent, so retrying is safe.
 *  - initiatePayout is the exception: retrying a money-moving call risks a double
 *    payout, so its retry is capped at 1 attempt and the three-state result is
 *    handled explicitly.
 *  - UNKNOWN -> Workflow.await (durable park) -> resume via reconcile() signal.
 */
class TransferWorkflowImpl : TransferWorkflow {

    @Volatile private var state: TransferState = TransferState.QUOTED
    @Volatile private var recon: ReconResult? = null

    // Most activities: auto-retry on failure (safe because they are idempotent).
    private val safe = Workflow.newActivityStub(
        TransferActivitiesGateway::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build(),
    )

    // Money-moving call: no automatic retry (double-payout prevention).
    private val payoutStub = Workflow.newActivityStub(
        TransferActivitiesGateway::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build(),
    )

    override fun execute(cmd: TransferCommand): TransferOutcome {
        val id = cmd.transferId
        val converted = cmd.source.currency != cmd.target.currency

        // 1) Risk gate
        state = TransferState.RISK_CHECK
        if (!safe.riskScreen(id, cmd.user, cmd.source)) {
            state = TransferState.REJECTED
            return TransferOutcome(id, state)
        }

        // 2) Pay-in
        safe.recordPayin(id, cmd.user, cmd.source)
        state = TransferState.FUNDS_IN

        // 3) FX
        if (converted) {
            safe.recordFx(id, cmd.user, cmd.source, cmd.target)
            state = TransferState.CONVERTED
        }

        // 4) Reserve payout
        safe.reservePayout(id, cmd.user, cmd.target)

        // 5) Call the rail (no-retry stub)
        val result = payoutStub.initiatePayout(id, cmd.target, cmd.user)

        when (result.status) {
            PayoutStatus.ACCEPTED -> {
                safe.finalizePayout(id, cmd.target)
                state = TransferState.PAID_OUT
            }
            PayoutStatus.REJECTED -> {
                safe.compensate(id, cmd.source, cmd.target, converted)
                state = TransferState.REFUNDED
            }
            PayoutStatus.UNKNOWN -> {
                // Timeout != failure. Park durably until reconciliation decides.
                state = TransferState.PAYOUT_UNKNOWN
                Workflow.await { recon != null }
                when (recon!!.outcome) {
                    ReconOutcome.CONFIRMED_SENT -> {
                        safe.finalizePayout(id, cmd.target)
                        state = TransferState.PAID_OUT
                    }
                    ReconOutcome.CONFIRMED_NOT_SENT -> {
                        safe.compensate(id, cmd.source, cmd.target, converted)
                        state = TransferState.REFUNDED
                    }
                }
            }
        }
        return TransferOutcome(id, state)
    }

    override fun reconcile(result: ReconResult) { this.recon = result }

    override fun currentState(): TransferState = state
}
