package com.crossremit.payment

import com.crossremit.payment.ledger.InMemoryLedger
import com.crossremit.payment.money.Money
import com.crossremit.payment.rails.MockRail
import com.crossremit.payment.rails.SettlementRecord
import com.crossremit.payment.reconciliation.Reconciliation
import com.crossremit.payment.transfer.Accounts
import com.crossremit.payment.transfer.TransferActivitiesImpl
import com.crossremit.payment.transfer.TransferOrchestrator
import com.crossremit.payment.transfer.TransferState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Saga + reconciliation behavior. TransferOrchestrator is the unit-testable twin
 * of the Temporal workflow — same sequence, no SDK required.
 */
class TransferTest {

    private fun orch(rail: MockRail, risk: (String, Money) -> Boolean = { _, _ -> true })
        : Pair<TransferOrchestrator, InMemoryLedger> {
        val lg = InMemoryLedger()
        return TransferOrchestrator(TransferActivitiesImpl(lg, rail, risk)) to lg
    }

    @Test fun `happy path same currency`() {
        val (o, lg) = orch(MockRail("accept"))
        val t = o.createTransfer("alice", Money.of("100", "USD"), Money.of("100", "USD"))
        o.execute(t)
        assertEquals(TransferState.PAID_OUT, t.state)
        assertTrue(lg.totalByCurrency().values.all { it == 0L })
    }

    @Test fun `happy path with FX`() {
        val (o, lg) = orch(MockRail("accept"))
        val t = o.createTransfer("bob", Money.of("100", "USD"), Money.of("130000", "KRW"))
        o.execute(t)
        assertEquals(TransferState.PAID_OUT, t.state)
        assertTrue(lg.totalByCurrency().values.all { it == 0L })
    }

    @Test fun `risk gate blocks money movement`() {
        val (o, lg) = orch(MockRail("accept"), risk = { _, _ -> false })
        val t = o.createTransfer("mallory", Money.of("100", "USD"), Money.of("100", "USD"))
        o.execute(t)
        assertEquals(TransferState.REJECTED, t.state)
        assertTrue(lg.totalByCurrency().isEmpty())  // no postings at all
    }

    @Test fun `reject triggers full compensation`() {
        val (o, lg) = orch(MockRail("reject"))
        val t = o.createTransfer("carol", Money.of("100", "USD"), Money.of("130000", "KRW"))
        o.execute(t)
        assertEquals(TransferState.REFUNDED, t.state)
        assertEquals(Money.zero("USD"), lg.balance(Accounts.user("carol", "USD")))
        assertTrue(lg.totalByCurrency().values.all { it == 0L })
    }

    @Test fun `unknown payout parks funds and does not compensate`() {
        val (o, lg) = orch(MockRail("timeout"))
        val t = o.createTransfer("dave", Money.of("100", "USD"), Money.of("100", "USD"))
        o.execute(t)
        assertEquals(TransferState.PAYOUT_UNKNOWN, t.state)
        assertEquals(-Money.of("100", "USD"), lg.balance(Accounts.settlement("USD")))
        assertTrue(lg.totalByCurrency().values.all { it == 0L })
    }

    @Test fun `execute is idempotent on retry`() {
        val (o, lg) = orch(MockRail("accept"))
        val t = o.createTransfer("erin", Money.of("100", "USD"), Money.of("100", "USD"))
        o.execute(t); o.execute(t)
        assertEquals(TransferState.PAID_OUT, t.state)
        assertEquals(-Money.of("100", "USD"), lg.balance(Accounts.nostroOut("USD")))
    }

    @Test fun `reconciliation confirms unknown as success`() {
        val rail = MockRail("timeout", settleOnTimeout = true)
        val (o, lg) = orch(rail)
        val t = o.createTransfer("frank", Money.of("100", "USD"), Money.of("100", "USD"))
        o.execute(t)
        Reconciliation(o, rail).run()
        assertEquals(TransferState.PAID_OUT, t.state)
        assertEquals(-Money.of("100", "USD"), lg.balance(Accounts.nostroOut("USD")))
    }

    @Test fun `reconciliation confirms unknown as failure and refunds`() {
        val rail = MockRail("timeout", settleOnTimeout = false)
        val (o, lg) = orch(rail)
        val t = o.createTransfer("grace", Money.of("100", "USD"), Money.of("100", "USD"))
        o.execute(t)
        Reconciliation(o, rail).run()
        assertEquals(TransferState.REFUNDED, t.state)
        assertTrue(lg.totalByCurrency().values.all { it == 0L })
    }

    @Test fun `reconciliation flags unmatched settlement as break`() {
        val rail = MockRail("accept")
        val (o, _) = orch(rail)
        rail.injectSettlement(SettlementRecord("MOCK-9999", Money.of("42", "USD"), "ghost"))
        val breaks = Reconciliation(o, rail).run()
        assertTrue(breaks.any { it.reason == "UNMATCHED_SETTLEMENT" })
    }
}
