# Photos performance: how we measure, what we found, what we tried

The record of performance work on the Photos database: the gallery's day queries, the shelves (library, archive,
bin) and albums. Every entry has a date, the platform and numbers. Record failed ideas too: knowing why something
didn't help is worth as much as a win. Chat and image work is in [`OPTIMIZE.md`](OPTIMIZE.md); thumbnails in
[`THUMBNAILS.md`](THUMBNAILS.md).

## How to measure

### PhotoGridDbBench on an Android phone

`PhotoGridDbBench` (homebase-api androidDeviceTest) builds a 40K-photo library plus 50K chat rows in a
SQLCipher database through the production upsert path, then times the queries a Photos grid needs: photos per
day, a photo count, and a page of headers near a date, each through `SqlDriver.executeQuery` with a fixed
identifier (prepared once, as SQLDelight's generated queries are), against today's `QueryBatch` page. The
database is kept between runs (about 3 minutes to build on a Pixel 5); `-e photoGridRebuild true` builds it again.

```bash
./gradlew :homebase-api:assembleAndroidDeviceTest
adb install -r -t homebase-api/build/outputs/apk/androidTest/homebase-api-androidTest.apk
adb shell am instrument -w -r -e class id.homebase.api.sync.database.PhotoGridDbBench -e photoGridBench true \
  id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/id.homebase.api.test/cache/photo-grid-bench.txt
```

The album benches run on the same database and leave it as they found it (they drop their tags and any index they
built):

```bash
# Tag counts and indexes: typical, one big album, heavy (runs longer than adb's patience: use -r and read the file)
adb shell am instrument -w -r -e class id.homebase.api.sync.database.PhotoGridDbBench#measureAlbums -e photoAlbumsBench true \
  id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/id.homebase.api.test/cache/photo-albums-bench.txt

# Query shapes on the heavy set, with and without extra indexes
adb shell am instrument -w -r -e class id.homebase.api.sync.database.PhotoGridDbBench#measureAlbumQueries -e photoAlbumQueriesBench true \
  id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/id.homebase.api.test/cache/photo-album-queries-bench.txt

# The shipped album queries (PhotoDays) against the tag-first shape, no new index; add -e photoAlbumCoversOnly true for covers alone
adb shell am instrument -w -r -e class id.homebase.api.sync.database.PhotoGridDbBench#measureAlbumRowIds -e photoAlbumRowIdsBench true \
  id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/id.homebase.api.test/cache/photo-album-rowids-bench.txt
```

Tag sets: favorites take every n-th row by `rowId` (rows went in shuffled, so they spread over the years); a trip
is a run of consecutive photos by `userDate`; the big album is 10,000 rows by `rowId`. The heavy set is 5,000
favorites, 50 trips of 400 and an album of 10,000: 35,000 tag rows on 40,000 photos.

## Baselines

### 2026-11-23, Photos grid database (PhotoGridDbBench), Pixel 5 (Android 14), synthetic 40K photos

40,000 photos on 2,519 days over 10 years, bursty, headers averaging 2,760 characters of JSON (four thumbnails,
the tiny, 8% Live photos, 10% videos), plus 50,000 chat rows: 323 MB. Medians of 15; Europe/Copenhagen.

| Query | Existing indexes | With a Photos index `(identityId,driveId,fileType,fileState,userDate DESC,rowId)` |
|---|---|---|
| Photos per day, `GROUP BY date(userDate/1000,'unixepoch','localtime')` | 58.7 ms | 59.0 ms |
| The same, first query on a fresh connection | 305 ms | 61 ms (run second, so not cold) |
| Photos per day, dates from SQL, grouped with kotlinx-datetime | | 1,101 ms |
| Photo count | 18.2 ms | 18.1 ms |
| 100 headers near 7 years back, fixed SQL | 6.0 ms | 1.8 ms |
| 30 headers (one screen), fixed SQL | | 0.7 ms |
| Parse 100 headers into `HomebaseFile` | | 33.7 ms |
| 100 headers + parse, fixed SQL | | 35.8 ms |
| 100 headers through `QueryBatch` (today's gallery), which parses them | 43.1 ms (p90 182) | |

- **The table is enough.** Counting per day uses `Idx3` already as a covering index; the Photos index only
  speeds up the header page (6.0 → 1.8 ms).
- **The day list is small:** 2,519 days as two `IntArray`s is 19 KB, so it can be held in memory once the
  gallery has opened.
- **Group in SQL, not in Kotlin:** kotlinx-datetime's `toLocalDateTime` over 40K dates took 1.1 s, against
  59 ms for SQLite's `localtime`, and the two agreed on every day.
- **Parsing is the cost of a page, not SQL:** 34 ms of a 36 ms page. A grid reading only what a tile needs
  would make it cheaper.
- Not measured: a truly cold read (the OS page cache can't be dropped without root), so the 305 ms first query
  includes an unknown share of connection setup.

### 2026-11-24, the shipped day queries (PhotoGridDbBench), same phone and library

`PhotoDays.sq` runs on today's indexes. The bench now also times it, and checks it counts the same days as its own
SQL. Medians of 15, after the phone cooled (thermal status 0).

| Query | Existing indexes |
|---|---|
| All days, `PhotoDays.dayCounts` (shipped) | 65.1 ms |
| All days, the bench's own SQL, same run | 62.6 ms |
| First day counts on a fresh connection | 80.2 ms |
| One day, `PhotoDays.dayOf`, the median day (7 photos) | 2.4 ms |
| One day, `PhotoDays.dayOf`, the busiest day (1,813 photos) | 42.0 ms |

- **A day's count costs about 23 µs per photo on it:** the range on `userDate` comes from `Idx2`, but each row is
  read for its `fileType` and `fileState`. A Photos index would make it covering; at these numbers it isn't needed,
  because `PhotoDayIndex` counts a day only while the gallery is open, once per day per batch of arriving photos,
  off the main thread.
- **The 305 ms first query of 2026-11-23 was the OS cache:** 80 ms this time without the Photos index.
- **Unexplained:** parsing 100 headers took 61 ms in both runs today against 34 ms on 2026-11-23, and the
  `QueryBatch` page 114 ms against 43 ms, on a cool phone. The SQL parts didn't change.
- **One day's count by `dayCounts`' own day expression** (`photosOnDay`, over a UTC range with a spare day each
  side), replacing local midnights from `strftime(…, 'utc')`, which SQLite's guessed summer time made disagree with
  the day counts before 1970 in some zones (Pacific/Kiritimati): median day 3.8 ms (was 2.7), busiest 46.5 ms (was
  42.8). A photo's day alone (`dayNumberOf`, no table) is 2.2 ms through the read lane.
- **Leaving out photos SQLite has no date for** (a `userDate` outside the years 0 to 9999, which `date()` turns into
  NULL), measured on all days:
  - `HAVING day IS NOT NULL`: 82.0 ms.
  - `AND userDate BETWEEN …`: 924 ms. SQLite switches to `Idx2` for the range, which doesn't cover `fileType` and
    `fileState`, so it reads all 40,000 rows.
  - `AND +userDate BETWEEN …` (shipped): 64.8 ms. The `+` stops SQLite using an index for that term, so it stays on
    the covering index.

### 2026-11-24, the library and the bin (PhotoGridDbBench), same phone and library

The bin is a photo with `archivalStatus = 2`, so every day query also filters on `archivalStatus` (0 for the library).
`Idx3` doesn't hold that column, so the filter took SQLite off its covering index. Medians of 15, thermal status 0.

| Query | Before | Filtered, today's indexes | Filtered, with `IdxPhotoShelf` (shipped) |
|---|---|---|---|
| All days, library | 62.6 ms | 937 ms | 63.4 ms |
| All days, bin (empty) | | 855 ms | 4.1 ms |
| One day, busiest day (1,813 photos) | 47.4 ms | | 8.5 ms |
| One day, median day | 3.9 ms | | 4.5 ms |
| Building the index, once per install | | | 1,488 ms |

- **`IdxPhotoShelf(identityId, driveId, archivalStatus, fileState, userDate) WHERE fileType = 7201 AND
  fileSystemType = 0`:** photos only, so chat writes don't keep it. A query uses a partial index only when it names
  the same terms itself, so the photo queries say `fileType = 7201` rather than taking it as a parameter.
- **No version bump:** `Schema.create` runs every `CREATE INDEX IF NOT EXISTS` on each open, so an installed app
  builds it at its first start with this version: 1.5 s on this 562 MB database, inside the database open at
  startup. A device with no photos still reads the table, but builds an empty index.

### 2026-11-25, albums: what a tag costs, and indexes (measureAlbums), same phone and library

Albums and Favorites are local tags (`DriveLocalTagIndex`, only `UNIQUE(identityId, driveId, fileId, tagId)`). The
shipped reads started from the tag rows (`CROSS JOIN`) and looked each photo up by `fileId`. Medians of 15.

| Read | Typical (1,500 tag rows) | One big album (13,000) | Heavy (35,000) |
|---|---|---|---|
| Library covers, all albums | 52 ms | 523 ms | 1,316 ms |
| The same with a tag-first index `(identityId, driveId, tagId, fileId)` | 47 ms | 287 ms | 1,043 ms |
| Favorites' day counts (500 / 2,000 / 5,000 photos) | 27 ms | 74 ms | 156 ms |
| A trip's day counts (100 / 100 / 400 photos) | 5 ms | 16 ms | 40 ms |
| The big album's day counts (10,000 photos) | | 270 ms | 255 ms |
| Building the tag-first index | 50 ms | 200 ms | 121 ms |

- **About 25 µs per tagged photo per read**, whatever the album: the cost is reaching each photo, not the tag
  scan. The tag-first index saves the scan of the drive's other tag rows (25 ms at 35,000) and little else.
- **A covering photo-id index** `(identityId, driveId, fileId, fileState, archivalStatus, userDate)`: SQLite used
  it and nothing got faster (Library 286 vs 287 ms); 1.8–2.6 s to build. With it, one Favorites page took 95 s:
  the planner walked it the wrong way. Dropped.
- **Cold first covers** (fresh connection): 191 ms → 73 ms typical with the tag index, 5.6 s → 1.5 s heavy; the
  OS page cache makes these unreliable run to run.

### 2026-11-26, albums: query shapes (measureAlbumQueries, measureAlbumRowIds), heavy set, SQLite 3.51.2

| Read | Tag rows first (before) | Shipped: shelf index first, the album as rowIds |
|---|---|---|
| Big album (10,000): day counts | 285 ms | 97 ms |
| Big album: a page of 19 days | 253 ms | 59 ms |
| Big album: one day's count | 252 ms | 68 ms |
| Favorites (5,000): day counts | 158 ms | 75 ms |
| Favorites: a page of 39 days | 144 ms | 51 ms |
| Favorites: one day's count | 142 ms | 54 ms |
| Trip (400): day counts | 38 ms | 48 ms |
| Trip: a page of 11 days | 40 ms | 37 ms |
| Trip: one day's count | 38 ms | 34 ms |
| Library covers, 51 albums | 1,314 ms | unchanged |

Shipped (`PhotoDays.sq`, "this build" lines): the tag's rows join the `fileId` unique index to give rowIds
without reading a photo (42 ms for 10,000, 26 ms for 400, most of it scanning the drive's 35,000 tag rows), then
the shelf index (`IdxPhotoShelf`, or `Idx2` for a page's date range) walks the photos and keeps the album's
(`+m.rowId IN (...)`), checking the rowId before reading the row. Only the album's photos on the shown days are
read. Through `PhotoDays` it costs 4–7 ms over the raw SQL.

- **Why it's faster:** the old shape read every tagged photo's row (about 20 µs each in a 570 MB SQLCipher
  database); counting the tag rows through the `fileId` index alone took 42 ms against 247 ms with the photo
  filters. The new one reads a row only for a photo it returns.
- **Small albums lose a little** (trip day counts 38 → 48 ms): the shelf walk is about 45 ms whatever the album,
  and it grows with the library: about 1.1 µs per photo, so near 225 ms for a 10-photo album in a 200,000-photo
  library (extrapolated, not measured), where the tag-first shape cost a few ms. Accepted for now (2026-11-26); if
  it bites, pick the shape by the album's size, or build the tag-first index.
- **With two new indexes** (`IdxPhotoShelf` + `fileId`, 1.5 s to build; tag-first, 0.12 s) the same shape was
  faster still: big album day counts 62 ms, pages 6.5 ms, Favorites 50 ms. Not built: no new index was the brief.

### Tried for albums, and why not

| Idea | Result |
|---|---|
| Tag ids first, then `fileId IN (500 ids)` per statement, merged in Kotlin | 17.6 s big album, 0.9 s trip: SQLite picks the shelf index for each chunk and walks the whole library |
| Shelf first, `m.fileId IN (tag subquery)` without a covering index | 926 ms: reads every photo's row for its `fileId` |
| A page: shelf first, `EXISTS` on the tag per photo | 19.6 ms big, 2.3 ms trip, but it reads every library photo in the window: a sparse album's page can span the whole library (estimated near the 926 ms full read; not measured) |
| Day counts by the album's rows in rowId order (`m.rowId IN` as the driver) | SQLite plans it as shelf first anyway: same time |
| Covers: shelf first, then each photo's tags | about 600 s |
| Covers: counts and newest date first, then one header per album | 1,083 ms, no better |
| Covers: the album rowIds `AS MATERIALIZED`, shelf first | 95 s: SQLite scans the materialized list per photo, with no automatic index |
| Covers: rowIds and shelf dates read separately, counted in Kotlin | 576 ms heavy, 2.3× faster, but it reads all 40,000 library dates whatever the albums, so a typical library would likely lose its 52 ms (not measured). Not shipped |
| A 64 MB page cache (`PRAGMA cache_size`) for the big album's day counts | 264 ms vs 259 ms at 2 MB: the cost isn't page misses |

- **Covers stay as they were:** 52 ms typical, 1.3 s at 35,000 tag rows. A fast heavy case needs either an index
  or counts kept per album as tags change.
