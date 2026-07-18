-- Double-entry ledger schema (Postgres).

CREATE TABLE IF NOT EXISTS account (
    id         TEXT PRIMARY KEY,
    acct_type  TEXT NOT NULL,
    currency   CHAR(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS journal_entry (
    id          BIGSERIAL PRIMARY KEY,
    reference   TEXT NOT NULL UNIQUE,               -- last line of defense for idempotency
    entry_type  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata    JSONB
);

CREATE TABLE IF NOT EXISTS posting (
    id           BIGSERIAL PRIMARY KEY,
    journal_id   BIGINT NOT NULL REFERENCES journal_entry(id),
    account_id   TEXT   NOT NULL REFERENCES account(id),
    amount_minor BIGINT NOT NULL,                   -- integer minor units; never floats
    currency     CHAR(3) NOT NULL,
    CONSTRAINT amount_nonzero CHECK (amount_minor <> 0)
);

CREATE INDEX IF NOT EXISTS idx_posting_account ON posting(account_id, id);
CREATE INDEX IF NOT EXISTS idx_posting_journal ON posting(journal_id);

-- Database-side balance enforcement: reject the commit if any journal entry's
-- per-currency sum is non-zero. This duplicates the application-level check on
-- purpose — if the code slips, the database still refuses.
-- The constraint trigger is DEFERRED so validation runs at commit time,
-- after all postings of the entry have been inserted.
CREATE OR REPLACE FUNCTION assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    bad RECORD;
BEGIN
    FOR bad IN
        SELECT currency, SUM(amount_minor) AS s
        FROM posting WHERE journal_id = NEW.journal_id
        GROUP BY currency HAVING SUM(amount_minor) <> 0
    LOOP
        RAISE EXCEPTION 'unbalanced journal % : % sum=%',
            NEW.journal_id, bad.currency, bad.s;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_posting_balanced ON posting;
CREATE CONSTRAINT TRIGGER trg_posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();
