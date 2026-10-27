# Idempotent Background Transaction Processing Service (FDE L2)

Java 17, Maven, no framework. Processes transaction requests that arrive **duplicated, out of order, and with transient failures** without double-applying anything.

## Run

```bash
mvn test                      # 24 unit tests
mvn package
java -jar target/idempotent-txn-processor-1.0.0.jar              # uses sample-data/transactions.csv
java -jar target/idempotent-txn-processor-1.0.0.jar my-file.csv  # your own input
```

## Design at a glance

```
TransactionSource (CSV / in-memory; swap for queue, DB poller, REST)
        |
TransactionProcessor  -- RequestValidator (pure)
        |                 IdempotencyStore (txnId + requestId)
        |                 per-account sequence buffer (PENDING_SEQUENCE)
        |                 retry loop (RETRY_PENDING, max attempts)
        v
Ledger (entry + balance committed atomically, unique entryId = transactionId)
```

| Requirement | How it is met |
|---|---|
| Idempotency | `transactionId` and `requestId` are checked before anything else. The ledger also refuses a second entry with the same id, so even a retry after a crash at the commit point cannot double-apply. |
| Duplicates arriving late | Dedup is by key lookup, not by position in the batch. First write wins; a duplicate with a different payload is logged and counted as a mismatch. |
| Out-of-order | **Option A, sequence-based**: `sequenceNumber` is per account, starting at 1. Future sequences are buffered as `PENDING_SEQUENCE` and released when the gap fills. Missing sequences are listed in the report. Order matters for real, e.g. a DEBIT that needs an earlier CREDIT. |
| Retries | Transient errors retry up to `maxAttempts` (default 3, linear backoff configurable). `RETRY_PENDING` between attempts, `FAILED(TRANSIENT_EXHAUSTED)` at the limit. |
| Consistency | Validation and balance checks happen first, then the fallible downstream hook, then a single atomic ledger commit. A failure before commit leaves nothing behind. |
| Recovery | `replayFailed(txnId)` re-runs an exhausted record. Safe because of the unique ledger entry id. |
| Invalid input | Rejected individually with field-level reasons (values are never echoed). The batch continues. |
| Observability | One-line `event=... txn=...` logs, per-record status history, and a summary with counts by status, failures, sequence gaps and balances. |
| Data handling | Account ids are masked in logs (`****1001`). No secrets in code. |

Status lifecycle: `RECEIVED -> [PENDING_SEQUENCE] -> PROCESSING -> [RETRY_PENDING -> PROCESSING]* -> PROCESSED | FAILED`.
`DUPLICATE` and `REJECTED` are terminal outcomes of inbound requests that never became a transaction.

## Trade-offs and assumptions

- **Sequence per account, starting at 1, gap-free.** Chosen because balances are order-dependent. Trade-off: one lost message stalls that account's later requests (visible in the gap report) until it arrives. A production version would add a gap timeout and alerting.
- **Failure does not block the account.** After a business failure or retry exhaustion the sequence cursor moves on, so one poison record cannot freeze the account. Trade-off: a replayed record is applied out of sequence. A stricter policy (halt the account on failure) is a one-line change in `drain`.
- **Reversals** reference an original transaction on the same account and currency, must match its amount, and work only once.
- **One balance per (account, currency).** Amounts are positive with at most 2 decimals.
- **In-memory stores**, single-writer (`synchronized`). Intentional for the exercise.
- Debits that would overdraw fail as a business error (not retried).

## Production-readiness notes (next steps)

1. Replace the stores with a database: `UNIQUE(transaction_id)` and `UNIQUE(request_id)` for idempotency, ledger insert and balance update in one DB transaction, `SELECT ... FOR UPDATE` per account.
2. Consume from a queue partitioned by `accountId`, which gives per-account ordering and lets workers scale horizontally. Add a dead-letter queue for exhausted records.
3. Persist the sequence cursor and buffered records so a restart does not lose pending items.
4. Exponential backoff with jitter, distinguishing retryable from non-retryable errors by exception type.
5. Metrics (Micrometer/Prometheus): counts by status, retry rate, buffer depth, oldest pending age. Structured JSON logs with a correlation id.
6. Input authentication and schema validation if exposed as an API.

## Sample output (`sample-data/transactions.csv`)

12 inputs: 6 PROCESSED, 2 DUPLICATE, 2 FAILED (T4 insufficient funds, T7 retries exhausted), 1 REJECTED (T10 bad amount), 1 PENDING_SEQUENCE (T9, waiting for sequence 4).
Final balances: ACC-1001 = 150.00, ACC-2002 = 225.00.

```
===== PROCESSING SUMMARY =====
Total requests received : 12
  RECEIVED         : 0
  PENDING_SEQUENCE : 1
  PROCESSING       : 0
  RETRY_PENDING    : 0
  PROCESSED        : 6
  FAILED           : 2
  DUPLICATE        : 2
  REJECTED         : 1
Rejected (invalid input):
  txn=T10 reasons=[amount is missing or not a number]
Failed transactions:
  txn=T4 kind=BUSINESS attempts=1 reason=INSUFFICIENT_FUNDS
  txn=T7 kind=TRANSIENT_EXHAUSTED attempts=3 reason=retries exhausted: simulated downstream timeout
Sequence gaps (buffered, waiting):
  account=****2002 missing sequence 4, buffered=[5]
Balances:
  ****1001 USD = 150.00
  ****2002 USD = 225.00
```

## AI usage

Built with AI assistance (Claude). Every design decision above was reviewed; the tests encode the intended behaviour.
