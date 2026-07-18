package com.crossremit.payment.ledger

import com.crossremit.payment.money.Money
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Production ledger backed by Postgres.
 *
 * Correctness properties:
 *  - Each journal entry is committed atomically in a single DB transaction.
 *  - journal_entry.reference UNIQUE is the last line of defense for idempotency —
 *    a retried write is rejected with 23505 (unique_violation) instead of double-posting.
 *  - Balance validation is enforced twice: in the application (assertBalanced)
 *    and in the database (constraint trigger in schema.sql) — if the code slips,
 *    the database still refuses.
 *
 * Uses only java.sql, so it compiles against the JDK alone; the Postgres JDBC
 * driver must be on the classpath at runtime (see build.gradle.kts).
 */
class JdbcLedger(private val ds: DataSource) : Ledger {

    override fun openAccount(accountId: String, type: String, currency: String) {
        tx { c ->
            c.prepareStatement(
                "INSERT INTO account(id, acct_type, currency) VALUES (?,?,?) " +
                    "ON CONFLICT (id) DO NOTHING"
            ).use { ps ->
                ps.setString(1, accountId); ps.setString(2, type); ps.setString(3, currency)
                ps.executeUpdate()
            }
        }
    }

    override fun post(reference: String, entryType: String, postings: List<Posting>): Long {
        assertBalanced(postings)
        return try {
            tx { c ->
                val journalId = c.prepareStatement(
                    "INSERT INTO journal_entry(reference, entry_type) VALUES (?,?) RETURNING id"
                ).use { ps ->
                    ps.setString(1, reference); ps.setString(2, entryType)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
                c.prepareStatement(
                    "INSERT INTO posting(journal_id, account_id, amount_minor, currency) " +
                        "VALUES (?,?,?,?)"
                ).use { ps ->
                    for (p in postings) {
                        ps.setLong(1, journalId)
                        ps.setString(2, p.account)
                        ps.setLong(3, p.amount.amountMinor)
                        ps.setString(4, p.amount.currency)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                journalId
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") throw DuplicateReference(reference)
            throw e
        }
    }

    override fun postIdempotent(reference: String, entryType: String, postings: List<Posting>): Long =
        try {
            post(reference, entryType, postings)
        } catch (e: DuplicateReference) {
            tx { c ->
                c.prepareStatement("SELECT id FROM journal_entry WHERE reference = ?").use { ps ->
                    ps.setString(1, reference)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }

    override fun reverse(originalReference: String, reversalReference: String): Long {
        val original = tx { c ->
            c.prepareStatement(
                "SELECT p.account_id, p.amount_minor, p.currency " +
                    "FROM posting p JOIN journal_entry j ON p.journal_id = j.id " +
                    "WHERE j.reference = ?"
            ).use { ps ->
                ps.setString(1, originalReference)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next())
                            add(Posting(rs.getString(1), Money(-rs.getLong(2), rs.getString(3))))
                    }
                }
            }
        }
        require(original.isNotEmpty()) { "Original entry not found: $originalReference" }
        return post(reversalReference, "REVERSAL", original)
    }

    override fun balance(accountId: String): Money = tx { c ->
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount_minor),0), " +
                "(SELECT currency FROM account WHERE id = ?) " +
                "FROM posting WHERE account_id = ?"
        ).use { ps ->
            ps.setString(1, accountId); ps.setString(2, accountId)
            ps.executeQuery().use { rs -> rs.next(); Money(rs.getLong(1), rs.getString(2)) }
        }
    }

    override fun totalByCurrency(): Map<String, Long> = tx { c ->
        c.prepareStatement("SELECT currency, SUM(amount_minor) FROM posting GROUP BY currency")
            .use { ps ->
                ps.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
                }
            }
    }

    /** Transaction boundary helper: autocommit off, commit on success, rollback on failure. */
    private fun <T> tx(block: (Connection) -> T): T {
        ds.connection.use { c ->
            val prev = c.autoCommit
            c.autoCommit = false
            try {
                val r = block(c)
                c.commit()
                return r
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = prev
            }
        }
    }
}
