package com.crossremit.payment.ledger

import com.crossremit.payment.money.Money

/**
 * In-memory ledger for tests and local experimentation — runs with zero infrastructure.
 * Production uses [JdbcLedger] (Postgres). Both satisfy the same [Ledger] contract.
 */
class InMemoryLedger : Ledger {

    private data class Account(val type: String, val currency: String)
    private data class Entry(val id: Long, val reference: String, val type: String)

    private val accounts = HashMap<String, Account>()
    private val entries = HashMap<String, Entry>()               // reference -> entry
    private val postingsByAccount = HashMap<String, MutableList<Money>>()
    private val postingsByReference = HashMap<String, List<Posting>>()
    private var seq = 0L

    override fun openAccount(accountId: String, type: String, currency: String) {
        accounts.putIfAbsent(accountId, Account(type, currency))
    }

    override fun post(reference: String, entryType: String, postings: List<Posting>): Long {
        assertBalanced(postings)
        if (entries.containsKey(reference)) throw DuplicateReference(reference)
        val id = ++seq
        entries[reference] = Entry(id, reference, entryType)
        postingsByReference[reference] = postings
        for (p in postings) {
            postingsByAccount.getOrPut(p.account) { mutableListOf() }.add(p.amount)
        }
        return id
    }

    override fun postIdempotent(reference: String, entryType: String, postings: List<Posting>): Long =
        try {
            post(reference, entryType, postings)
        } catch (e: DuplicateReference) {
            entries.getValue(reference).id
        }

    override fun reverse(originalReference: String, reversalReference: String): Long {
        val original = postingsByReference[originalReference]
            ?: throw IllegalArgumentException("Original entry not found: $originalReference")
        val reversed = original.map { Posting(it.account, -it.amount) }
        return post(reversalReference, "REVERSAL", reversed)
    }

    override fun balance(accountId: String): Money {
        val currency = accounts[accountId]?.currency
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val total = postingsByAccount[accountId]?.sumOf { it.amountMinor } ?: 0L
        return Money(total, currency)
    }

    override fun totalByCurrency(): Map<String, Long> {
        val sums = HashMap<String, Long>()
        for (list in postingsByAccount.values)
            for (m in list) sums.merge(m.currency, m.amountMinor, Long::plus)
        return sums
    }
}
