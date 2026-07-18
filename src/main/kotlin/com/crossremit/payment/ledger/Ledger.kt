package com.crossremit.payment.ledger

import com.crossremit.payment.money.Money

/**
 * Double-entry ledger.
 *
 * The single core invariant: within one journal entry, postings must sum to zero
 * per currency. As long as this holds, money can never be created or destroyed
 * anywhere in the system.
 *
 * Sign convention: debit (+), credit (-). The ledger is append-only —
 * corrections are made by posting reversals, never by deleting.
 * A balance is not state; it is derived from postings.
 *
 * Implementations: [InMemoryLedger] (tests/learning) and [JdbcLedger] (Postgres, production).
 */
interface Ledger {
    fun openAccount(accountId: String, type: String, currency: String)

    /**
     * Record one journal entry atomically.
     * @throws UnbalancedEntry if per-currency sums are non-zero
     * @throws DuplicateReference if the reference already exists (idempotency guard)
     */
    fun post(reference: String, entryType: String, postings: List<Posting>): Long

    /** Idempotent variant: silently returns the existing journal id on duplicates. */
    fun postIdempotent(reference: String, entryType: String, postings: List<Posting>): Long

    /** Offset the original entry by appending opposite postings (compensation). */
    fun reverse(originalReference: String, reversalReference: String): Long

    fun balance(accountId: String): Money

    /** Integrity check: per-currency totals across all accounts must always be zero. */
    fun totalByCurrency(): Map<String, Long>
}

/** A single posting: move [amount] on [account]. Debit (+) / credit (-). */
data class Posting(val account: String, val amount: Money)

class UnbalancedEntry(bad: Map<String, Long>) :
    RuntimeException("Per-currency sums are non-zero: $bad")

class DuplicateReference(reference: String) :
    RuntimeException("Duplicate reference: $reference")

/** Balance validation shared by implementations. */
internal fun assertBalanced(postings: List<Posting>) {
    val sums = HashMap<String, Long>()
    for (p in postings) sums.merge(p.amount.currency, p.amount.amountMinor, Long::plus)
    val bad = sums.filterValues { it != 0L }
    if (bad.isNotEmpty()) throw UnbalancedEntry(bad)
}
