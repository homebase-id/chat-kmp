# Performance: how we measure, what we found, what we tried

The record of performance work, so the same ground isn't covered twice. Every entry has a
date, a commit, the platform, and numbers. Record failed ideas too: knowing why something
didn't help is worth as much as a win.

## How to measure

### DB benchmark (desktop JVM, headless)

```bash
DB_BENCH=1 ./gradlew :homebase-api:jvmTest --tests '*DbPerfBench*' --rerun -q
# DB_BENCH_ITERATIONS=100 for tighter numbers
# DB_BENCH_DRIVER=legacy runs SQLDelight's connection-per-statement driver for an A/B
```

- `ChatDbFixture` builds a synthetic SQLCipher database through the production sync upsert
  path (`performBaseUpsert`): 150 conversations, 57K messages spread Zipf-style (largest
  ~12.6K), 3K files on another drive, 15% of messages with payload thumbnails, 10% with
  reactions. About 192 MB, about 1.9 KB of `jsonHeader` per row. It's generated once into
  `homebase-api/build/db-bench/` and reused; delete the file to regenerate it.
- `DbPerfBench` opens it with the same URL and connection properties desktop production uses,
  and times the queries behind each user flow (open conversation, scroll up, conversation list,
  unread counts, orphan scan, incoming upsert). It prints median / p95 / min, then the
  `EXPLAIN QUERY PLAN` of every SQL statement it ran. The report is also written to
  `homebase-api/build/db-bench/results-*.md`.
- Skipped unless `DB_BENCH=1`, so CI never runs it.
- Absolute numbers move with machine load (the same build measured unread counts at 30 ms and
  later 67 ms). Only compare runs made back to back.
- Close to production but not identical: real headers carry fields the fixture doesn't
  (transfer history, link previews). Compare runs with each other, not with a device.

### On a device

`homebase.log` already carries:

- `StartupLogger checkpoint: …` markers for cold start (diff the timestamps).
- `SlowDbRead queueWait=… sql=… mapper=…` for any read over 50 ms. `queueWait` is waiting
  for a read slot, `sql` is SQLite, `mapper` is row mapping and JSON.
- `SlowMessageFetch` when a conversation's fetch plus mapping takes over 200 ms.

## Baselines

### 2026-10-23, d0bbc2e18, desktop JVM (Linux), synthetic fixture v1

Median / p95 in ms. The desktop driver opens a new JDBC connection per statement (see below).

| Flow | median | p95 |
|---|---|---|
| open `DatabaseManager` | 13.1 | 13.3 |
| open conversation, first 75 (largest, 12.6K msgs) | 7.4 | 8.1 |
| open conversation, first 75 (median, 493 msgs) | 7.3 | 7.4 |
| open conversation, first 75 (small, 54 msgs) | 3.2 | 3.3 |
| scroll up, next 75 (largest) | 7.2 | 7.7 |
| conversation list + last message | 16.9 | 21.6 |
| unread counts | 30.5 | 62.9 |
| orphaned-at-rest scan | 238.8 | 344.4 |
| incoming message upsert (1 row) | 13.5 | 18.5 |

All plans use indexes. The chat fetch seeks `idx_chatmessage_convid_userDate`, so conversation
size barely matters. The per-conversation SQL isn't the slow part of opening a conversation
on desktop.

## Findings

- **SQLite mode is right.** WAL, `synchronous=NORMAL`, `busy_timeout=5000`,
  `temp_store=MEMORY`, shared as `SQLITE_JOURNAL_MODE` and `SQLITE_CONNECTION_PRAGMAS`. No better journal
  mode exists for one writer and many readers. `mmap_size` doesn't apply: SQLCipher pages are
  encrypted.
- **`adb shell dumpsys dbinfo` doesn't list the app's database.** It only shows databases
  opened through `android.database.sqlite`; SQLCipher has its own pool. The
  `DB pragmas: …` line in `homebase.log` shows the pragmas of one connection, not how many
  are open, so check concurrency with `DatabaseConnectionContractAndroidTest`.
- **A passphrase costs a key derivation per connection.** SQLCipher runs PBKDF2 × 256,000 on
  every connection it opens with a passphrase, and Android's pool opens readers one at a time.
  Desktop and Android hand it a raw key derived once (`SqlCipherKeyMode`). iOS doesn't encrypt
  yet; when it does, start in raw-key mode.
- **Desktop prepares every statement again.** `JdbcDriver` ignores the statement
  `identifier`; Android's and iOS's drivers cache prepared statements per connection. Now that
  desktop connections live long, a per-connection cache keyed by `identifier` would save a
  prepare per statement. Not measured.
- **`cache_size` is unset** (default about 2 MB per connection).
- **Desktop cold start.** The 2026-10-18 log showed about 12 s on an empty DB (DB→Koin
  3.5 s), but that was a cold JVM. On 2026-10-23, with a warm build and the pooled driver:
  `main()`→DB 0.4 s, →Koin 1.3 s, →Compose entered 1.9 s. Measure on a packaged build before
  chasing Koin.
- **Opening a conversation is more than its fetch** (code trace, 2026-10-23; not yet
  measured on a device). `ConversationListViewModel.loadMessagesForConversation` only
  starts collecting `observeMessages` after `loadConversation` /
  `loadConversationAroundMessage` return. Before returning, those run `onMessagesSurfaced`
  (auto-pin: a tag write on the single writer lane plus an outbox enqueue, serially, for each
  unvoted poll, unfinished event or live location that isn't pinned or dismissed yet, so
  usually none; and the view-once sweep, which strips opened or expired view-once media) and
  `refreshPinnedFor` (the pinned query, which
  `unpinExpiredPins` already ran once). So the first frame can queue behind DriveSync writes.
  A saved scroll anchor (the common reopen) takes `loadAround`: one single-row read plus two
  sequential 37-row fetches. `mapToMessageData` does no crypto; content is plaintext in the
  header. The tap → first-messages time is already logged at Info:
  `messages first emission id=… sinceSelected=…` (ConversationListViewModel). Check that on
  the phone before changing anything.

## Tried

| Date | Change | Result | Kept? |
|---|---|---|---|
| 2026-10-23 | Desktop: `PooledJdbcSqliteDriver` keeps connections open. SQLDelight's `JdbcSqliteDriver` closed the connection after every statement outside a transaction, so each one re-read the schema, re-applied pragmas and started with a cold page cache. Back-to-back A/B, median ms legacy → pooled: open DB 16.8 → 3.6, open largest conversation 10.8 → 4.4, small 7.1 → 1.7, conversation list 14.9 → 8.0, incoming insert 17.5 → 3.0. Unread counts (~30) and orphan scan unchanged: CPU-bound, not connection-bound | Desktop only; Android/iOS already keep connections open | Yes |
| 2026-10-23 | Bound the WAL: `journal_size_limit` = 4 MB in the shared pragmas, plus `wal_checkpoint(TRUNCATE)` after `VACUUM`. Kept connections never delete the `-wal`, and a `VACUUM` writes the whole database into it | Desktop and Android (Android was 10 KB from SQLCipher's default); iOS from the connection contract below | Yes |
| 2026-10-23 | Orphan scan (runs once per database lifetime, delayed past boot): its `NOT EXISTS` probe searched `idx_unread_cover (identityId, fileType)`, which lacks `uniqueId`, so every message row walked the conversation headers (240–390 ms). Tried: add `h.driveId = m.driveId` so the header probe seeks the UNIQUE(identityId, driveId, uniqueId) index | **925 ms**, about 3x worse. The probe seeks, but the outer 57K-row scan is no longer covered by `idx_unread_cover` (no `driveId`), so every row costs a table lookup into ~2 KB rows. Lesson: on this table a covering scan beats a faster probe | No |
| 2026-10-23 | Orphan scan: `GROUP BY` in a subquery first, then `NOT EXISTS` per group (about 150 probes instead of 57K). Same rows, since the probe depends only on `groupId` | 340 → 28 ms | Yes |
| 2026-10-23 | One connection contract (`DatabaseDriverFactory.createDriver`): one writer plus `SQLITE_READER_CONNECTIONS` (4) readers under WAL, every connection carrying `SQLITE_CONNECTION_PRAGMAS`. Android had a single connection: `SupportOpenHelperFactory(password)` leaves `enableWriteAheadLogging` off, so SQLCipher caps the pool at 1 whatever the file's journal mode, and every read, opening a conversation included, waited for the open sync write transaction. Now the WAL flag is on, the pool is 5, a per-connection hook sets the pragmas on readers and `onConfigure` re-applies them on the writer (SQLCipher overwrites `journal_size_limit` to 10 KB after the hook). iOS gets the pragmas it lacked (`temp_store`, `journal_size_limit`) through sqliter's per-connection callback | `DatabaseConnectionContractAndroidTest` on an API 36 emulator: before, "reads waited for the open write transaction" (15 s timeout); after, passes in 1.6 s. Without the hook readers had `busy_timeout` 2500; without `onConfigure` the writer had `journal_size_limit` 10000. Not yet measured on a phone | Yes |
| 2026-10-24 | Android raw-key mode: the shared `SqlCipherKeyMode` (`javaMain`, desktop and Android) derives the raw key once from the passphrase and the file's salt, and caches it. The file isn't rewritten | API 36 emulator, per fresh driver, passphrase → raw key: open plus first read 220–307 → 2–6 ms; 4 first reads during a write 766–988 → 5–9 ms (the pool opened 3 readers one after another, each deriving the key). With more readers but a passphrase, the connection contract above would have stalled the first parallel reads at cold start by about a second | Yes |
