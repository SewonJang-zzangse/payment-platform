package com.crossremit.payment

import com.crossremit.payment.ledger.DuplicateReference
import com.crossremit.payment.ledger.InMemoryLedger
import com.crossremit.payment.ledger.Posting
import com.crossremit.payment.ledger.UnbalancedEntry
import com.crossremit.payment.money.CurrencyMismatch
import com.crossremit.payment.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Ledger invariants: balance rule, idempotency, append-only reversals. */
class LedgerTest {
    private fun ledger() = InMemoryLedger().apply {
        openAccount("NOSTRO:USD", "NOSTRO", "USD")
        openAccount("USER:a:USD", "USER_BALANCE", "USD")
    }

    @Test fun `balanced entry updates balances`() {
        val lg = ledger()
        lg.post("r1", "PAYIN", listOf(
            Posting("NOSTRO:USD", Money.of("100.00", "USD")),
            Posting("USER:a:USD", -Money.of("100.00", "USD"))))
        assertEquals(Money.of("100.00", "USD"), lg.balance("NOSTRO:USD"))
        // User balances are liabilities (credit-normal), hence negative.
        assertEquals(-Money.of("100.00", "USD"), lg.balance("USER:a:USD"))
    }

    @Test fun `unbalanced entry is rejected`() {
        val lg = ledger()
        assertThrows(UnbalancedEntry::class.java) {
            lg.post("bad", "PAYIN", listOf(
                Posting("NOSTRO:USD", Money.of("100.00", "USD")),
                Posting("USER:a:USD", -Money.of("99.99", "USD"))))  // attempts to vanish one cent
        }
    }

    @Test fun `duplicate reference prevents double posting`() {
        val lg = ledger()
        val ps = listOf(
            Posting("NOSTRO:USD", Money.of("50.00", "USD")),
            Posting("USER:a:USD", -Money.of("50.00", "USD")))
        lg.post("rx", "PAYIN", ps)
        assertThrows(DuplicateReference::class.java) { lg.post("rx", "PAYIN", ps) }
        lg.postIdempotent("rx", "PAYIN", ps)  // silently absorbed
        assertEquals(Money.of("50.00", "USD"), lg.balance("NOSTRO:USD"))
    }

    @Test fun `reversal is append-only and nets to zero`() {
        val lg = ledger()
        lg.post("orig", "PAYIN", listOf(
            Posting("NOSTRO:USD", Money.of("30.00", "USD")),
            Posting("USER:a:USD", -Money.of("30.00", "USD"))))
        lg.reverse("orig", "orig-rev")
        assertEquals(Money.zero("USD"), lg.balance("NOSTRO:USD"))
        assertEquals(Money.zero("USD"), lg.balance("USER:a:USD"))
    }

    @Test fun `system-wide currency sum is zero`() {
        val lg = ledger()
        lg.post("a", "PAYIN", listOf(
            Posting("NOSTRO:USD", Money.of("12.34", "USD")),
            Posting("USER:a:USD", -Money.of("12.34", "USD"))))
        assertEquals(0L, lg.totalByCurrency()["USD"])
    }

    @Test fun `money rejects currency mismatch`() {
        assertThrows(CurrencyMismatch::class.java) {
            Money.of("1", "USD") + Money.of("1", "EUR")
        }
    }
}
