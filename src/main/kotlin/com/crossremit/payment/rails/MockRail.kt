package com.crossremit.payment.rails

/**
 * Mock rail for tests and local runs. Failure scenarios can be forced deterministically.
 *
 * @param outcome "accept" | "reject" | "timeout"
 * @param settleOnTimeout when the outcome is timeout (UNKNOWN), whether the money
 *        actually left — surfaced later through the settlement records
 */
class MockRail(
    private val outcome: String = "accept",
    private val settleOnTimeout: Boolean = false,
) : RailAdapter {

    override val railId = "MOCK"
    override val capabilities = setOf(Capability.INSTANT)

    private var counter = 0
    private val settlements = mutableListOf<SettlementRecord>()
    private val seen = HashMap<String, PayoutResult>()   // rail-side idempotency store

    override fun initiatePayout(cmd: PayoutCommand): PayoutResult {
        seen[cmd.idempotencyKey]?.let { return it }

        val ref = "%s-%04d".format(railId, ++counter)
        val result = when (outcome) {
            "accept" -> {
                settlements += SettlementRecord(ref, cmd.amount, cmd.beneficiary)
                PayoutResult(PayoutStatus.ACCEPTED, ref, "ok")
            }
            "reject" -> PayoutResult(PayoutStatus.REJECTED, null, "insufficient rail liquidity")
            "timeout" -> {
                // UNKNOWN: the caller must not retry or compensate.
                if (settleOnTimeout) settlements += SettlementRecord(ref, cmd.amount, cmd.beneficiary)
                PayoutResult(PayoutStatus.UNKNOWN, ref, "gateway timeout")
            }
            else -> throw IllegalArgumentException("unknown outcome: $outcome")
        }
        seen[cmd.idempotencyKey] = result
        return result
    }

    override fun queryStatus(railReference: String): PayoutStatus =
        if (settlements.any { it.railReference == railReference }) PayoutStatus.ACCEPTED
        else PayoutStatus.UNKNOWN

    override fun fetchSettlement(): List<SettlementRecord> = settlements.toList()

    /** Test hook: inject a phantom settlement record. */
    fun injectSettlement(record: SettlementRecord) { settlements += record }
}
