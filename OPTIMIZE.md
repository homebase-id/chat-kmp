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

### DB operations per flow (JVM, deterministic, runs in CI)

```bash
./gradlew :homebase-chat:jvmTest --tests '*DbOpsBaselineTest*'
DB_OPS_UPDATE=1 ./gradlew :homebase-chat:jvmTest --tests '*DbOpsBaselineTest*'   # accept a change
```

- `CountingSqlDriver` (homebase-chat `jvmTest`) records every statement a flow runs, whether it
  ran inside a transaction, and the three app frames that issued it ("helper < caller < entry
  point"). The flow runs on runTest's own dispatcher, `ChatMessageStream`'s `mapDispatcher`
  included, so the callers stay on the thread's stack and nothing leaves virtual time.
- `DbOpsBaselineTest` drives each flow through the real services on an in-memory database and
  compares the statements with `homebase-chat/src/jvmTest/resources/db-ops/<flow>.txt`. A change
  fails the test; the diff in the PR shows what changed and why.
- Counts, not time: they don't move with machine load, so CI can hold them.
- Covered so far: opening a conversation (fresh, and around a saved message), an incoming
  message, a read receipt, sending a message (offline outbox), a background wake (drive sync).
  Not covered yet: ConversationListViewModel's own reads when opening (it needs Koin), the
  push-tap path, mark-as-read, typed messages (polls, events) that auto-pin.

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

### 2026-10-26, 004c805e5, iOS baseline, unencrypted, debug K/N framework

iPhone (A16, `iosApp Dev`) and iPhone 17 simulator on an M-series Mac. The release framework
link ran out of Gradle heap (`linkReleaseFrameworkIosArm64`, `OutOfMemoryError`), so these
are a debug Kotlin/Native framework: compare them with each other, not with Android or desktop.
Phone DB: 8.4 MB, 1,936 `DriveMainIndex` rows, 309 messages, 70 conversations, WAL, no
`sqlite_stat1`. Medians of 3 runs, ms from the `homebase.log` markers.

| Flow | median |
|---|---|
| phone cold start: process → `APP STARTUP` | 346 |
| phone `initializeApp` (DB init 6–9) | 34–51 |
| phone → `ComposeView` built (waits for scene `.active`, main thread idle) | 716 |
| phone → conversation list data ready (70 items) | 840 |
| phone `loadBasicConversations` (SQL 1–3, row mapping the rest) | 50–67 |
| phone `enrichWithLastMessages` | 34–65 |
| phone incoming message, WebSocket `drainOnce` | 5.5 |
| sim open conversation, `messages first emission sinceSelected`, 56 msgs | 129 |
| sim open conversation, 6 msgs | 110 |

The three hot queries (list + last message, unread counts, open conversation) all seek an
index on the phone DB; the list query runs in 6 ms in the `sqlite3` CLI. Every cold start on
the phone logs one `SlowDbRead`: the `QueryBatch` page, with `mapper` 46–76 ms of JSON decode.

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
  yet; when it does, start in raw-key mode (see `openNativeSqliteDriver`).
- **Desktop prepares every statement again.** `JdbcDriver` ignores the statement
  `identifier`; Android's and iOS's drivers cache prepared statements per connection. Now that
  desktop connections live long, a per-connection cache keyed by `identifier` would save a
  prepare per statement. Not measured.
- **Tag `DELETE`s per synced row** (not tried): one `DELETE … WHERE fileId IN (…)` per tag table
  for a batch's written rows, chunked under SQLite's variable limit, would keep the UNIQUE safety
  and drop most of the 2 statements per row. Measure with DbPerfBench first.
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

- **DB operations per flow**, snapshot of 2026-10-24 (the live counts head each
  `homebase-chat/src/jvmTest/resources/db-ops/*.txt`), 100-message conversation:

  | Flow | reads | writes | transactions |
  |---|---|---|---|
  | open a conversation | 2 | 0 | 0 |
  | open around a saved message | 4 | 0 | 0 |
  | incoming message (conversation open) | 1 | 3 | 1 |
  | read receipt, newer `modified` | 1 | 3 | 1 |
  | read receipt, equal `modified` (server-owned fields adopted) | 2 | 2 | 1 |
  | send a text message | 1 | 5 | 1 |
  | background wake, 3 new messages | 1 | 10 | 1 |

  Every incoming batch, read receipt and send re-reads the pinned bar (`refreshPinnedFor`)
  for the open conversation, whether or not anything pin-related changed. A send's outbox insert
  runs outside the transaction of its optimistic write. Opening a conversation at the service
  level is the message page and the pinned bar; the ViewModel adds `unpinExpiredPins` and the
  pinned observer on top (not measured yet).

- **iOS (2026-10-26, debug framework).** Opening a conversation is composition, not DB: on
  the simulator the main thread spends 400–700 ms in `ConversationContent` and the list pane,
  and a 6-message conversation opens about as fast as a 56-message one. At launch the phone's
  main thread is not saturated; the background does `CacheAudit`/`CacheSweeper` (~250 ms CPU),
  the gallery prefetch in `GalleryCache` init (`fetchGalleryImages`, ~240 ms) and the
  `QueryBatch` JSON decode (~130 ms). Crashlytics' report setup costs 49 ms on the main thread
  in `dladdr` (debug symbols; recheck on release).
- **Each send causes three server writes**: the message, `stampConversationDraft` (GET + PATCH
  of the conversation's local metadata) and `stampConversationLastReadTime` ~2.3 s later, so
  every other device drains the conversation header twice per message.
- **Profiling pitfall:** with argent's accessibility service attached to the simulator,
  `AccessibilityMediator.sync` took 56% of main-thread samples while scrolling (45 saturated
  50 ms windows); without it, 4 samples and 2 windows. Launch with `simctl` and don't query the
  AX tree while recording.
- **`SlowDbRead` previews were unidentifiable**: 120 characters of `QueryBatch`'s SQL are the
  same for every caller. Now 400.

## Tried

| Date | Change | Result | Kept? |
|---|---|---|---|
| 2026-10-23 | Desktop: `PooledJdbcSqliteDriver` keeps connections open. SQLDelight's `JdbcSqliteDriver` closed the connection after every statement outside a transaction, so each one re-read the schema, re-applied pragmas and started with a cold page cache. Back-to-back A/B, median ms legacy → pooled: open DB 16.8 → 3.6, open largest conversation 10.8 → 4.4, small 7.1 → 1.7, conversation list 14.9 → 8.0, incoming insert 17.5 → 3.0. Unread counts (~30) and orphan scan unchanged: CPU-bound, not connection-bound | Desktop only; Android/iOS already keep connections open | Yes |
| 2026-10-23 | Bound the WAL: `journal_size_limit` = 4 MB in the shared pragmas, plus `wal_checkpoint(TRUNCATE)` after `VACUUM`. Kept connections never delete the `-wal`, and a `VACUUM` writes the whole database into it | Desktop and Android (Android was 10 KB from SQLCipher's default); iOS from the connection contract below | Yes |
| 2026-10-23 | Orphan scan (runs once per database lifetime, delayed past boot): its `NOT EXISTS` probe searched `idx_unread_cover (identityId, fileType)`, which lacks `uniqueId`, so every message row walked the conversation headers (240–390 ms). Tried: add `h.driveId = m.driveId` so the header probe seeks the UNIQUE(identityId, driveId, uniqueId) index | **925 ms**, about 3x worse. The probe seeks, but the outer 57K-row scan is no longer covered by `idx_unread_cover` (no `driveId`), so every row costs a table lookup into ~2 KB rows. Lesson: on this table a covering scan beats a faster probe | No |
| 2026-10-23 | Orphan scan: `GROUP BY` in a subquery first, then `NOT EXISTS` per group (about 150 probes instead of 57K). Same rows, since the probe depends only on `groupId` | 340 → 28 ms | Yes |
| 2026-10-23 | One connection contract (`DatabaseDriverFactory.createDriver`): one writer plus `SQLITE_READER_CONNECTIONS` (4) readers under WAL, every connection carrying `SQLITE_CONNECTION_PRAGMAS`. Android had a single connection: `SupportOpenHelperFactory(password)` leaves `enableWriteAheadLogging` off, so SQLCipher caps the pool at 1 whatever the file's journal mode, and every read, opening a conversation included, waited for the open sync write transaction. Now the WAL flag is on, the pool is 5, a per-connection hook sets the pragmas on readers and `onConfigure` re-applies them on the writer (SQLCipher overwrites `journal_size_limit` to 10 KB after the hook). iOS gets the pragmas it lacked (`temp_store`, `journal_size_limit`) through sqliter's per-connection callback | `DatabaseConnectionContractAndroidTest` on an API 36 emulator: before, "reads waited for the open write transaction" (15 s timeout); after, passes in 1.6 s. Without the hook readers had `busy_timeout` 2500; without `onConfigure` the writer had `journal_size_limit` 10000. Not yet measured on a phone | Yes |
| 2026-10-24 | Android raw-key mode: the shared `SqlCipherKeyMode` (`javaMain`, desktop and Android) hands SQLCipher a cached raw key. The first launch after the update opens with the passphrase and derives the raw key on a background thread, because Android's PBKDF2 runs in Java: 3,261 ms on the main thread when tried inline, 1,705 ms in the background on an API 36 emulator. The database opens once; the old separate key probe also checkpointed the WAL on the main thread. The file isn't rewritten | Per fresh driver, passphrase → raw key: open plus first read 220–307 → 2–6 ms; 4 first reads during a write 766–988 → 5–9 ms (the pool opened 3 readers one after another, each deriving the key). Upgrade from the previous build on the emulator: migrated, salt unchanged, next launch silent | Yes |
| 2026-10-24 | Sync batch: save the drive cursor once per batch, not once per file (`performBaseUpsert`) | `background-wake.txt` 12 → 10 writes for 3 files. Linux, Python's sqlite3 (no SQLCipher): 1,000 redundant upserts cost 6.8 ms per batch, plus serializing the cursor each time | Yes |
| 2026-10-24 | Skip the two tag `DELETE`s (`DriveTagIndex`, `DriveLocalTagIndex`) for new rows | Same setup: 2,000 no-match deletes over 57K tag rows cost 4.0 ms per 1,000-file batch. Skipping needs proof that no tag rows exist for the fileId; if that is wrong, the tag `INSERT` breaks UNIQUE and the batch fails | No: risk outweighs 4 ms |
<<<<<<< HEAD
| 2026-10-26 | `QueryBatch`: the read mapper only collects the `jsonHeader` strings; the `HomebaseFile` JSON decode runs after the read on `DatabaseManager.decodeDispatcher`, so a reader connection is not held during the decode (phone `SlowDbRead` mapper was 46–76 ms per cold start) | Not yet measured on device (the rebuild did not finish) | Pending |
=======
| 2026-10-26 | Rich text: one markdown parse per message (`ChatMarkdownParseCache`, LRU 256 keyed by content, default instance via `LocalChatMarkdownParseCache`). Before, a block bubble ran 3 `MarkdownParser` passes per composition entry (`markdownHasBlockElements`, `withChatHardLineBreaks`, mikepenz `rememberMarkdownState`) and an inline bubble 3 (same two, then `buildMarkdownAnnotatedString`), plus `MessageBubbleRaw`'s own `markdownHasBlockElements`: 4 per bubble, all repeated when the LazyColumn recycled the item. After: 1 parse for a body with no paragraph soft breaks, 2 for one with (the promoted text has different offsets, so it is parsed again), once per content for the life of the cache. `ChatMarkdownParseCacheTest` counts the cache's parse seam: 1 across restyle and dispose/re-add. The search-highlight path still strips with its own CommonMark parse (different flavour, active only while searching) | Parse count, not timed on a device | Yes |
>>>>>>> origin/main
