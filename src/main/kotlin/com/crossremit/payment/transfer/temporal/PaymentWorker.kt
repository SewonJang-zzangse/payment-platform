package com.crossremit.payment.transfer.temporal

import com.crossremit.payment.money.Money
import com.crossremit.payment.rails.PayoutResult
import com.crossremit.payment.transfer.TransferActivitiesImpl
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory

/**
 * Temporal activity implementation: delegates to the pure core (TransferActivitiesImpl).
 * Registers the workflow and activities on a worker polling the task queue.
 */
class TemporalActivitiesImpl(
    private val core: TransferActivitiesImpl,
) : TransferActivitiesGateway {
    override fun riskScreen(transferId: String, user: String, amount: Money) =
        core.riskScreen(transferId, user, amount)
    override fun recordPayin(transferId: String, user: String, source: Money) =
        core.recordPayin(transferId, user, source)
    override fun recordFx(transferId: String, user: String, source: Money, target: Money) =
        core.recordFx(transferId, user, source, target)
    override fun reservePayout(transferId: String, user: String, target: Money) =
        core.reservePayout(transferId, user, target)
    override fun initiatePayout(transferId: String, target: Money, beneficiary: String): PayoutResult =
        core.initiatePayout(transferId, target, beneficiary)
    override fun finalizePayout(transferId: String, target: Money) =
        core.finalizePayout(transferId, target)
    override fun compensate(transferId: String, source: Money, target: Money, converted: Boolean) =
        core.compensate(transferId, source, target, converted)
}

const val TRANSFER_TASK_QUEUE = "TRANSFER_TQ"

/**
 * Worker bootstrap. In production, wire the core through DI:
 * DataSource -> JdbcLedger plus real RailAdapter implementations.
 */
fun startWorker(core: TransferActivitiesImpl) {
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val client = WorkflowClient.newInstance(service)
    val factory = WorkerFactory.newInstance(client)
    val worker = factory.newWorker(TRANSFER_TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(TransferWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(TemporalActivitiesImpl(core))
    factory.start()
}

/**
 * Example entry point. Start a local Temporal server first
 * (`temporal server start-dev`), then run this worker.
 */
fun main() {
    val ledger = com.crossremit.payment.ledger.InMemoryLedger()
    val rail = com.crossremit.payment.rails.MockRail("accept")
    val core = TransferActivitiesImpl(ledger, rail)
    startWorker(core)
    println("Transfer worker started on task queue '$TRANSFER_TASK_QUEUE'")
}
