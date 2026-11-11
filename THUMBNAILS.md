# Thumbnails and media images

How chat-kmp turns a photo into the images people see. Read this before changing anything that creates, sizes, scales, encodes or picks an image, or strips its metadata.

> **Status (2026-11-08).** This is the agreed design. `main` still runs the old ladder (320, 640, 1080 and 1600, with byte budgets). Everything not built yet is listed in [To do, and what isn't settled](#to-do-and-what-isnt-settled) at the end.
>
> | Issue or PR | What it covers |
> |---|---|
> | #2221 | Goals for the pipeline |
> | #2230 | Spec for the settings and the table |
> | PR #2231 | Implements the pipeline (draft) |
> | PR #2232 | Implements the table (draft) |
> | #2235 | Fixes from review of #2231 and #2232 |
> | #2236 | The resampler |

## Contents

1. [The goal](#the-goal)
2. [What's settled](#whats-settled)
3. [The three settings](#the-three-settings)
4. [The table](#the-table)
5. [Why these resolutions](#why-these-resolutions)
6. [Why these qualities](#why-these-qualities)
7. [Changing the table](#changing-the-table)
8. [Working out the sizes](#working-out-the-sizes)
9. [Rules for edge cases](#rules-for-edge-cases)
10. [Worked examples](#worked-examples)
11. [Byte limits](#byte-limits)
12. [Making the pixels](#making-the-pixels)
13. [Colour](#colour)
14. [Metadata](#metadata)
15. [Formats](#formats)
16. [Where each caller lands](#where-each-caller-lands)
17. [Showing the images](#showing-the-images)
18. [Wire format and older clients](#wire-format-and-older-clients)
19. [Video](#video)
20. [How others do it](#how-others-do-it)
21. [Testing](#testing)
22. [Decisions we made, and the alternatives we rejected](#decisions-we-made-and-the-alternatives-we-rejected)
23. [To do, and what isn't settled](#to-do-and-what-isnt-settled)

## The goal

**Make the best possible thumbnails on iOS and Android, using as little of the phone as possible. Still in a unified API that spans all platforms including JVM and WASM.**

### Why the phones come first

The photo library is synced from the phones. Homebase Photos backs up every photo on an iPhone or Android phone, often thousands of them, in short background windows, on battery, while the phone is doing other things. That's where nearly all image work happens, and where it costs the most.

Thumbnails are made on that phone, and they're encrypted. The server can never regenerate them, so whatever the phone uploads is what every client sees, forever. Getting them wrong can only be fixed by generating and uploading them again.

So, on iOS and Android:

1. **Scaling quality is paramount.** Thumbnails must be as sharp and clean as they can be, with no aliasing, moiré or blockiness. The scaler is the part that decides this.
2. **Use native code where it gives an advantage.** That means native APIs where possible, such as ImageIO and Accelerate on iOS and `ImageDecoder` on Android, and a native library where the platform has nothing good enough. "Advantage" means measurably better quality, speed, memory or battery. Native code that isn't better buys nothing. If there's a library that works as good as native code, we'll prefer a library
3. **Use the phone's resources as efficiently as possible:**
   - **Read and decode each image once.** Never read the same file four times to make four thumbnails.
   - **Decode only as large as the largest output needs.**
   - **Keep memory bounded:** no extra full-size copies of the pixels, even for 48 MP and larger photos.
   - **Keep CPU low:** no wasted resampling, and no re-encoding that gains nothing.

### The other platforms

Desktop and web follow the same table, rules and quality bar, so every platform produces the same sizes. They make far fewer images, though, so speed and memory matter less there. Shared code is fine, even if it's slower. Image quality should preferably be equally good on JVM & WASM.

### What "good" means everywhere

- **Readable and sharp:** screenshots stay readable on Standard, on a phone and on a 2× desktop monitor.
- **Predictable size:** bytes come from pixels and quality chosen per setting, never from byte budgets that silently wreck quality.
- **Never regress:** everything chat-kmp does well today keeps working. #2230 has the checklist.

## What's settled

These are agreed. Change one only with the evidence described in [Changing the table](#changing-the-table).

- **Three settings:** Standard, HD and Original, as in [The three settings](#the-three-settings).
- **The table:** sizes and qualities for every row and setting, as in [The table](#the-table).
- **Sizing:** long edge, plus a minimum short edge and a longest edge allowed, worked out with integer math before any pixels are touched.
- **Never upscale.** A row within 10% of the source is made at the source size, with no scaling.
- **Only three byte limits:** the tiny's 768 bytes, 1 MiB per thumbnail (the server's), and none on the final image. Over 1 MiB, a thumbnail shrinks at the same quality.
- **Pixels:** decode once, scale from largest to smallest, halve while the image is at least 2× too big, then a Lanczos-class resampler for the last step.
- **Colour:** keep wide-gamut colour, and put no colour profile on sRGB images or the tiny.
- **Metadata:** Photos keeps everything on the final image, chat strips everything, and Moments asks. Thumbnails never carry metadata. The image API takes a required keep-or-strip parameter, so each caller chooses.
- **Display:** step up from the largest thumbnail to the final image only when that gains pixels. Photobase's grid always uses Small.
- **Wire codes:** Standard records nothing, HD records `"hd"`, and Original records `"high"`.

## The three settings

| Setting | What people expect | Photo | Video |
|---|---|---|---|
| **Standard** (default) | Looks good, quick to send, screenshots readable | 2048 px, WebP q60 | 720p |
| **HD** | Stays sharp when zoomed | 4096 px, WebP q80 | 1080p |
| **Original** | The exact file | The file, untouched | The file, untouched (see [Video](#video)) |

**Names.** Every screen uses exactly these names: chat, Moments, the share sheet, settings, badges and Photos. They're **Standard**, **HD** and **Original**, in every language, all from one place (`MediaQualityLabels.kt`). Descriptions quote this table's numbers, for example "Photos up to 2048 pixels". Change the table, and its names and descriptions change with it.

**Why three.** Two settings forced a choice between "small" and "the exact file". The old "High" really meant the exact file, but it was badged "HD". People who want sharp photos without multi-megabyte originals had no setting. Three settings match what WhatsApp, Signal and Google Photos offer (see [How others do it](#how-others-do-it)).

## The table

All sizes are WebP. In each column, the last filled row is that setting's **final image**, "the photo". Everything above it is a thumbnail.

| Row | Long edge | Minimum short edge | Longest edge allowed | Standard | HD | Original |
|---|---|---|---|---|---|---|
| Tiny (768 bytes max) | 20 | – | 20 | q76 | q76 | q76 |
| Small | 320 | 240 | 640 | q70 | q80 | q80 |
| Medium | 640 | 480 | 1280 | q70 | q80 | q80 |
| Large | 1080 | 810 | 2160 | q70 | q76 | q80 |
| Extra large | 2048 | 1080 | 8192 | **q60, final** | q70 | q76 |
| High definition | 4096 | 2160 | 8192 | – | **q80, final** | – |
| Original file | – | – | – | – | – | **Untouched, final** |

Words used in this document:

| Word | Meaning |
|---|---|
| **Source** | The file the user picked, at its displayed size after EXIF rotation. |
| **Tiny** | The 20 px WebP embedded in the file's metadata (`previewThumbnail`), shown blurred while the real image loads. |
| **Thumbnail** | A smaller image stored next to the payload, so clients don't download the whole photo. |
| **Final image, or "the photo"** | The payload: what the recipient saves or zooms into. |
| **Row** | One line of the table. |

## Why these resolutions

Each row has a job. A change to a row should show that the row still does its job, or does a more important one better.

| Row | Its job | Why this size |
|---|---|---|
| **Tiny, 20 px** | A blurred placeholder on the first frame, before anything downloads | Enough for the right colours and shape, and small enough to embed in every file's metadata within 768 bytes. Never contains a color map |
| **Small, 320 px** | Grid cells and small previews | A grid cell on a phone is about 380 px. Photobase's grid always uses this row, so a small rise here costs bytes on every photo in the library. |
| **Medium, 640 px** | Chat bubbles on phones | A phone bubble is about 600–900 physical pixels wide. |
| **Large, 1080 px** | Full phone width, and bubbles on desktop | Matches the width of most phone screens. |
| **Extra large, 2048 px** | Standard's photo, and a big thumbnail in HD and Original | **Screenshots need pixels.** At the old 1600 px, a phone screenshot was 738 px wide, and small text blurred; at 2048 it's 1080 px wide and readable. In HD and Original, it's the thumbnail desktop bubbles and full-screen views use first. |
| **High definition, 4096 px** | HD's photo: sharp when zoomed | The same as WhatsApp HD and Signal High. At 4096×3072, about 12.6 MP, it's close to Google Photos' 16 MP storage saver. |
| **Original file** | The exact file | Nothing is lost. It's the only choice for people who want their camera's file. |

**Why size by the long edge, with a minimum short edge and a longest edge allowed:**

- **The long edge alone** keeps sizes predictable, but turns odd shapes into slivers. A 1179×8000 screenshot at 320 on the long edge is 47 px wide.
- **The short edge alone,** which is what Immich does, handles odd shapes. But sizes become unpredictable: a panorama's "250 px" thumbnail can be 3000 px long.
- **So both, with a cap.** Aim for the long edge, grow until the short edge reaches its minimum, and never pass the longest edge allowed. That keeps a sliver useful and a panorama bounded.
- The minimum is ¾ of the long edge, so ordinary 4:3 photos are unaffected, and 16:9 and 3:2 photos grow slightly, which is intended. For example, 4000×2250 gets 427×240 instead of 320×180.

## Why these qualities

| Setting | Qualities | Why |
|---|---|---|
| **Standard** | q70 thumbnails, q60 photo | **Text needs pixels, not quality:** by eye, text stayed clean at q50. Photos at 2× zoom looked as good at 2048 q60 as at the old 1600 q85, and were smaller: 222 KB against 294 KB median. Thumbnails are viewed smaller than their pixels, so q70 is plenty. |
| **HD** | q80 small thumbnails, q76 Large, q70 Extra large, q80 photo | People chose sharpness. The big Extra large thumbnail can be lower, because the 4096 photo replaces it when it matters. |
| **Original** | q80 small thumbnails, q80 Large, q76 Extra large | People chose the best. The thumbnails are all they see until the full file loads, so they're the highest of the three. |
| **Tiny** | q76, dropping as needed | It's shown blurred. Only its byte limit matters. |

Two conditions apply:

- **The low qualities assume a proper resampler.** Aliasing adds noise, which costs bytes and looks bad at q60. Don't ship these qualities on a bilinear-only scaler (see [Making the pixels](#making-the-pixels)).
- **Not yet checked on real phone photos,** especially faces, skin and night shots. q60 and q70 may go up after #2230's real-photo test.

**Measured on test photos,** 12 stock 12 MP JPEGs plus generated screenshots. These aren't phone photos, so real numbers will probably be higher.

| Median per photo | Bytes | Final image |
|---|---|---|
| Old Standard (320 q84, 640 q84, 1600 q85), with proper resampling | 365 KB | 1600×1200 |
| New Standard | 359 KB | 2048×1536 |
| HD | 1135 KB, of which 742 KB is the photo | Up to 4096×3072 |
| Original | 441 KB of thumbnails, plus the file | The file |

## Changing the table

The table is settled, not frozen. A change should come with:

1. **The reason:** which row's job, or which goal above, it serves better.
2. **Evidence on real phone photos,** recorded in `OPTIMIZE.md`: bytes, plus by-eye comparisons at 1× and 2× zoom, covering faces, night shots and 12, 24 and 48 MP photos. Add a small-text screenshot checked on a phone and on a desktop monitor at 1× and 2×.
3. **The cost:** bytes per photo times a whole library. Small and Medium are on every photo, so a change there multiplies.
4. **The limits still hold:**
   - at most 5 thumbnails;
   - no thumbnail over 1 MiB in normal photos;
   - the tiny within 768 bytes;
   - older clients still read the result.
5. **Updated [worked examples](#worked-examples), tests and this document.**

Existing images aren't regenerated, so a change only affects new uploads. That's safe, because every client reads any thumbnail size.

## Working out the sizes

Sizing is pure integer math on the source's **displayed** width and height, after EXIF rotation. Nothing is decoded or scaled at this step. `L` and `S` are the source's long and short edges. Use 64-bit integers.

```text
long = rowLong
if S × rowLong < rowMinShort × L:      # too thin: grow until the short edge reaches its minimum
    long = ceil(rowMinShort × L / S)
long = min(long, rowLimit, L)          # never past the longest edge allowed, never upscale
if long × 10 >= L × 9:                 # within 10% of the source: keep the source size
    long = L
short = max(1, round(S × long / L))
```

Then put `long` and `short` back as width and height in the source's orientation, and apply the rules below. The tiny skips the 10% step: it is always at most 20 px.

**Properties that must always hold.** Tests check these on random sizes:

- no output is larger than its source or smaller than 1 px;
- outputs never get smaller going down the table;
- any output, other than the tiny, whose long edge is at least 90% of the source's is exactly the source size.

## Rules for edge cases

| Case | Rule | Why |
|---|---|---|
| Source smaller than a row | **Never upscale.** The row comes out at the source size | Upscaling adds no detail. It costs bytes, and makes the image softer, because the screen resamples it a second time. |
| A row is at least 90% of the source | Make it at the source size, **with no scaling**. Thumbnails and the final image, not the tiny | A 2–10% reduction resamples every pixel, which softens the image, to save at most a fifth of them. |
| Several thumbnails come out the same size | Keep one, at the highest quality among them | Duplicates are wasted bytes. |
| A thumbnail is at least 90% of the next larger one | Drop the smaller; the larger gets the higher quality | Near-duplicates are wasted bytes. |
| Source smaller than every row | Still make one thumbnail at source size | Older clients need a thumbnail; see below. |
| Comparing with the final image | The final image never counts in the 90% rules | Thumbnails must exist even when the final image is the same size, because existing clients only load the payload directly for GIFs (`HomebaseImageLoader.kt`, `THUMBLESS_CONTENT_TYPES`). |
| Final image at least 90% of the largest thumbnail | It gets at least that thumbnail's quality | The photo should never look worse than its own thumbnail. |
| Source already fits Standard or HD, 10% band included | Keep the source if it's JPEG, PNG or WebP, unless re-encoding saves at least a quarter | Re-encoding a file that already fits only adds generational loss, and costs CPU. |
| HEIC source on Standard or HD | Re-encode at source size, without scaling, if it fits | Browsers can't decode HEIC. |
| Source can't be decoded | Send the file with no thumbnails | Better than failing the send. |
| Very thin image | Never below 1 px on either edge | |
| Sticker, GIF, SVG or animated image | The file stays as the final image | Re-encoding would break animation or vectors. See [GIFs and animated images](#gifs-and-animated-images). |
| GIF | Tiny only, with no other thumbnails | Clients play the GIF file itself. See [GIFs and animated images](#gifs-and-animated-images). |
| RAW photo (DNG, Apple ProRAW, camera formats) | See [RAW photos](#raw-photos) | |
| SVG | Render the rows from the vector, ignoring the source-size limit, and keep the aspect ratio. If rendering fails, no thumbnails | A vector has no native size, so rendering it larger is free. |

### GIFs and animated images

**GIFs, animated or not, are always sent and stored as the GIF file.** In every setting:

- **The final image is the GIF file, untouched.** Re-encoding with our still-image encoders would freeze an animation on its first frame. A still GIF is also kept, because the format alone can't tell clients whether it's animated.
- **Only the tiny is made, from the first frame.** It reports the GIF's full size, not the tiny's 20 px, because receivers use it to lay out the bubble before the GIF loads.
- **No other thumbnails.** Clients always load and play the GIF file itself: `image/gif` is in `HomebaseImageLoader.THUMBLESS_CONTENT_TYPES`, so they never ask for a thumbnail. That's why a GIF must never be mislabelled as another type. A pasted GIF keeps its `.gif` name so it doesn't become `image/png` and freeze.
- **A pasted animated GIF** offers the choice of sending it as a GIF or as a sticker.

**Other animated images:**

- **Animated WebP** keeps its file as the final image in every setting, because the still encoder would freeze it. It gets the normal still thumbnails from its first frame, so today it shows still in a bubble and only animates where the full file loads.
- **APNG, animated HEIC and animated AVIF** keep their file as the final image too, detected by `ImageFormatDetector.isAnimated`.

**Cost.** A GIF has no small version, so every place that shows it downloads the whole file. That includes Photobase's grid, which otherwise never loads more than Small. See [To do](#to-do-and-what-isnt-settled).

### RAW photos

RAW files hold the camera's unprocessed sensor data:

- **Formats:** DNG, which covers Apple ProRAW and most Android phones that shoot RAW, plus camera makers' own formats such as CR3, NEF, ARW and RAF.
- **Size:** typically 25–75 MB each.
- **Browsers can't show them,** and every app has to render them before showing them: demosaicing, white balance and tone.

| Where | What we do | Why |
|---|---|---|
| **Photos (Photobase)** | **Back up the RAW file untouched,** like every other original | It's the user's own original, and often the best copy they have. Google Photos, iCloud and Immich all keep RAW files. |
| Thumbnails in Photos | Made on the phone from the rendered image. Use the RAW's embedded preview JPEG when it's at least as large as the largest thumbnail, because it's fast and is the camera's own rendering. Otherwise render the RAW, which ImageIO does on iOS and `ImageDecoder` does for DNG on Android | Rendering a 48 MP RAW is slow and memory-hungry, while the embedded preview is usually ready to use. |
| A RAW the phone can't decode | Back it up untouched, with no thumbnails, like any undecodable file | The file is never lost, and the album shows a placeholder. Android only decodes DNG, not camera makers' formats. |
| **Chat, Standard or HD** | Render it and send WebP at the setting's size, like HEIC | Recipients' browsers and apps can't show RAW. |
| **Chat, Original** | Send the RAW file, with thumbnails if the sending device can decode it | Original means the exact file. |
| Desktop and web | Skia can't decode RAW, so a RAW sent from there gets no thumbnails | Phones are where RAW comes from. |

**RAW+JPEG pairs.** Some camera apps save both a RAW and a JPEG of the same shot:

- **iOS** keeps them as one photo, with the RAW as an extra resource. Photobase backs up the photo resource, the JPEG or HEIC (`IosDeviceChangeSource`), so **today the RAW half of a pair isn't backed up.** A ProRAW photo on its own is backed up, because the DNG is its main resource.
- **Android** saves two separate files, and both are backed up as separate photos.

How pairs should be stored and shown is in [To do](#to-do-and-what-isnt-settled).

## Worked examples

Each cell lists the thumbnails, then the final image after the arrow. They come from the planning script and are exact tests.

| Source | Standard | HD | Original |
|---|---|---|---|
| 4000×3000 | 320×240 q70, 640×480 q70, 1080×810 q70 → 2048×1536 q60 | 320×240 q80, 640×480 q80, 1080×810 q76, 2048×1536 q70 → 4000×3000 q80 | 320×240 q80, 640×480 q80, 1080×810 q80, 2048×1536 q76 → file |
| 4000×2250 | 427×240, 854×480, 1440×810 → 2048×1152 | 427×240, 854×480, 1440×810, 2048×1152 → 4000×2250 | Same thumbnails → file |
| 1179×2556 (iPhone screenshot) | 240×521, 480×1041, 810×1757 → 1179×2556 | 240×521, 480×1041, 810×1757, 1179×2556 → 1179×2556 | Same thumbnails → file |
| 1179×8000 (long screenshot) | 94×640, 189×1280, 318×2160 → 1179×8000 | 94×640, 189×1280, 318×2160, 1179×8000 → 1179×8000 | Same thumbnails → file |
| 12000×4000 (panorama) | 640×213, 1280×427, 2160×720 → 3240×1080 | 640×213, 1280×427, 2160×720, 3240×1080 → 6480×2160 | Same thumbnails → file |
| 1100×825 | 320×240, 640×480, 1100×825 → 1100×825 q70 | 320×240, 640×480, 1100×825 q76 → 1100×825 q80 | 320×240, 640×480, 1100×825 q80 → file |
| 1200×900 (band edge) | 320×240, 640×480, 1200×900 → 1200×900 q70 | 320×240, 640×480, 1200×900 q76 → 1200×900 q80 | 320×240, 640×480, 1200×900 q80 → file |
| 1201×901 (just outside) | 320×240, 640×480, 1080×810 → 1201×901 q60 | 320×240, 640×480, 1080×810, 1201×901 → 1201×901 | Same thumbnails → file |
| 2275×1706 (band edge) | 321×241, 641×481, 1081×811 → 2275×1706 | 321×241, 641×481, 1081×811, 2275×1706 → 2275×1706 | Same thumbnails → file |
| 2276×1707 (just outside) | 320×240, 640×480, 1080×810 → 2048×1536 | 320×240, 640×480, 1080×810, 2048×1536 → 2276×1707 | Same thumbnails → file |
| 4551×3413 / 4552×3414 (HD band edge) | … → 2048×1536 | … → 4551×3413, and 4096×3072 just outside | … → file |
| 600×450 | 320×240, 600×450 → 600×450 | 320×240, 600×450 → 600×450 | 320×240, 600×450 → file |
| 200×150 | 200×150 → 200×150 | 200×150 → 200×150 | 200×150 → file |

Qualities are omitted where they follow the table directly. The 321×241 in the 2275×1706 row isn't a typo: 1706 × 320 / 2275 is just under 240, so the minimum short edge pushes the row up one pixel.

## Byte limits

There are exactly three, and two of them are the server's.

| What | Limit | Where it comes from | When it's over |
|---|---|---|---|
| Tiny | **768 bytes** | Ours. Odin-core rejects the whole upload over 1024 (`ThumbnailContent.MaxTinyThumbLength`) | Lower quality, all the way to q1 if needed, then shrink and check again, down to 1×1. This must be a real guarantee. |
| Each thumbnail | **1 MiB**, less a little for encryption | Odin-core's `ThumbnailDescriptor.MaxThumbnailSize`. Odin-core also allows at most 5 thumbnails (`PayloadDescriptor.MaxThumbnailsCount`), and the table makes at most 4 | **Shrink, keep the quality.** If the 10% rule enlarged it, go back to the row's normal size first. Otherwise shrink by an estimate (about √(limit ÷ bytes), with a margin) and check again. Lower quality only for noise-like images, and never below q60 before shrinking further. |
| Final image | **None** | Odin-core's general payload limit, 50 GB by default | – |

**Why shrink rather than drop quality.** The 1 MiB only bites on HD's and Original's Extra large thumbnail, typically a long screenshot or a very detailed photo. Those settings always have a full-size final image, which the step-up loads when a display needs more pixels. So a slightly smaller thumbnail costs nothing visible, while q40 blockiness would show. Standard's thumbnails never get near 1 MiB.

**Why the tiny drops quality instead.** It's a 20 px placeholder shown blurred, so detail doesn't matter and its size does.

**Why the limits are the same in every setting.** The limits are hard ceilings set by the server, not tuning knobs. The settings already differ in pixels and quality, and a lower limit for Standard would never trigger.

## Making the pixels

One entry point, `renderImages(source, plan)` in `homebase-api`:

1. **Read the displayed size** from the file header, without decoding.
2. **Plan** every output with the integer math above. The planner never touches pixels.
3. **Decode once,** only as large as the largest output needs. On phones, the platform's decoder can shrink during decoding: ImageIO for any format, Android's `ImageDecoder` for JPEG.
4. **Scale from largest to smallest,** each size made from the one above it, and encode each. In a test, chaining was twice as fast as scaling each size from the source, with nearly identical results: 54 dB median difference, and about 0.5% more bytes.

### How to resize well

**What goes wrong.** Shrinking an image means each new pixel stands for a patch of old pixels: at 2×, a 2×2 patch; at 1.5×, a patch one and a half pixels across. A good resizer blends every old pixel in that patch. A cheap one only looks at the few old pixels nearest each new pixel's centre, and skips the rest. Skipped pixels don't disappear quietly. Fine patterns turn into false patterns: moiré on fabric and screens, jagged or broken letters in screenshots, and shimmering fences and hair. That's called aliasing. It also adds noise, which costs bytes, so it makes files bigger as well as uglier.

**How we catch it.** Draw 1-pixel black and white stripes and shrink them. Each new pixel covers some black and some white, so a correct resizer must produce flat grey. A cheap resizer produces stripes or moiré again. That's the checkerboard test and the stripe test in [Testing](#testing). A second test with 8-pixel stripes checks the opposite mistake: a resizer that simply blurs everything would pass the first test, but turns those stripes grey too.

**The recipe.** This is best practice, used by Pillow, libvips and image CDNs:

1. **Plan all sizes first,** with integer math on the upright (orientation-applied) size. Never resize to discover a size.
2. **Decode once.** Let the decoder do a coarse reduction only when it averages properly: ImageIO on iOS, but only by 2× or more, because it aliases below that (1.43× leaves a stripe spread of 20, 1.34× of 30, 2× about 3); and JPEG decoding on Android, which can shrink by 2, 4 or 8. Never use decoding that skips pixels, which is Android's HEIC, PNG and WebP sampling, except as a memory guard for huge images. The guard is shared: over 24 MP, Android and iOS both decode smaller, but never below what the largest output needs. On iOS the guard is the one thing that can force ImageIO under 2×: HD on a 48 MP photo needs 4096 from 8064, so ImageIO shrinks 1.43× and aliases until native resizing (#2301) lands.
3. **Filter with premultiplied alpha.** Blending transparent and opaque pixels without premultiplying gives dark or coloured fringes around cut-outs and stickers.
4. **While the image is at least 2× too big, halve it** by averaging each 2×2 block. That's exact, cheap and blends every pixel.
5. **Do the last step, under 2×, with a Lanczos-3 filter whose width grows with the reduction.** It blends the whole patch and keeps edges crisp. It runs the pass that shrinks more first, so the in-between copy is the smaller one, and pauses every 8 ms so the web page, where it shares the UI thread, keeps drawing. The phones run it natively on the bitmap's own pixels (Accelerate on iOS, `stb_image_resize2` on Android), so there's no heap copy and no softer fallback; desktop and web run the shared Kotlin Lanczos. Mitchell is an acceptable alternative if it passes the same tests: slightly softer, with less ringing around hard edges like text. Fixed-size filters (bilinear, or Skia's cubic) and a plain box average fail the test; see the table below.
6. **Chain from largest to smallest.** Each size is made from the pixels of the size above it, never from an encoded, lossy file.
7. **Don't resample when it gains nothing.** Never upscale, and make a row at the source size when it's within 10% of it.
8. **Resample in the image's own colour space,** sRGB or Display P3, as nearly every tool does. Resampling in linear light is slightly more correct for bright detail on dark backgrounds, but it costs twice the memory and CPU, so we don't, unless a real-photo test shows a visible gain.
9. **No extra sharpening.** Lanczos is already crisp, and sharpening adds halos around edges and costs bytes.
10. **Encode each size once,** from its pixels, at its quality. Never re-encode a lossy result to make it smaller.

### Scaling: halve, then a proper resampler

- **While an image is at least 2× too big, halve it.** An exact 2× bilinear step averages every pixel, and it's cheap.
- **The last step, under 2×, needs a resampler whose reach grows with the reduction,** such as Lanczos-3. This is the step real photos almost always end on: a 12 MP photo to 2048 is 1.97×, 2048 to 1080 is 1.90×, and 1080 to 640 is 1.69×.

Results on 1-pixel black and white stripes, which a correct filter turns flat grey. The number is the standard deviation of the output; 0 is perfect, and about 73 means the stripes passed through at full contrast:

| Filter | 4032→2048 (1.97×) | 1080→640 (1.69×) | 3000→2048 (1.46×) | 2560→2048 (1.25×) |
|---|---|---|---|---|
| Bilinear (#2231 as drafted) | 73.4 | 73.3 | 73.8 | 71.1 |
| Skia Mitchell | 69.0 | 69.0 | 69.1 | 69.0 |
| Skia Catmull-Rom | 88.9 | 88.8 | 88.8 | 88.6 |
| Skia bilinear with mipmaps | 73.4 | 73.3 | 73.8 | 71.1 |
| Box (area average), Pillow | 22.5 | 71.3 | 93.3 | 110.4 |
| **Lanczos, Pillow** | **0.9** | **1.6** | **1.4** | **21.8** |

None of Skia's sampling modes is good enough, and neither is a simple area average. Android's `Bitmap.createScaledBitmap` is Skia underneath, so it has the same limits. Lanczos also keeps coarser detail at full contrast, which a blur would not.

### On the phones: native where it's better

| | iOS | Android |
|---|---|---|
| **Decode** | ImageIO, `CGImageSourceCreateThumbnailAtIndex`: reads HEIC and RAW directly, applies orientation, and decodes only as large as asked | `ImageDecoder`, which applies orientation and reads HEIC directly, with BitmapFactory as the fallback. JPEG shrinks properly while decoding (by 2, 4 or 8); HEIC, PNG and WebP shrinking just skips pixels, so they need a memory guard (#2235) |
| **Scale** | Accelerate's `vImageScale_ARGB8888` with its default Lanczos-3, between the Skia images' own raster pixels (`ImageResample.native.kt`). `kvImageHighQualityResampling` would pick Lanczos-5, which costs more and differs from the other platforms | No system high-quality resampler, so `stb_image_resize2` with a Lanczos-3 kernel through the NDK, on `AndroidBitmap_lockPixels` (`homebase-imageresize`, its own Android library because the KMP Android plugin can't build native code) |
| **Encode** | WebP through Skia's bundled libwebp, since iOS has no system WebP encoder | `Bitmap.compress`, unless a bundled libwebp measures better |
| **Pixel hand-off** | Draw straight into the destination's native memory, never into a Kotlin `ByteArray`, which is up to 50 MB on the Kotlin/Native heap (#2236) | Bitmaps already live in native memory |

The rule on phones: native code wherever it's measurably better in quality, speed, memory or battery, and an existing, proven library before writing our own. As of 2026-11-08 there's no Kotlin Multiplatform library with a proper resampler.

### Desktop and web

Same decode-once pipeline and the same quality bar, on Skia. The last scaling step uses `stb_image_resize2` if it's easy to bundle; otherwise a small shared Kotlin Lanczos. Speed matters less here.

### The contract

Every platform has one interface, `ImageSource` plus `PipelineBitmap`, whose KDoc states the behaviour. That includes the filter quality: 1-pixel detail averages to grey at any ratio, and coarse detail keeps its contrast. The contract tests run the real code on all four platforms (see [Testing](#testing)).

## Colour

- **Wide-gamut photos keep their colour.** A Display P3 photo either keeps its profile or is converted properly; it's never just relabelled as sRGB, which makes it look washed out.
- **sRGB images carry no profile.** An image with no profile is read as sRGB everywhere, and Skia's sRGB profile alone is 456 bytes.
- **The tiny never carries a profile.** In #2231's draft, the profile made up 456 of a 594-byte tiny, and the picture was 92 bytes.

## Metadata

| Where | Final image ("the photo") | Thumbnails and tiny |
|---|---|---|
| **Photos (Photobase)** | **Keeps everything,** in every setting: GPS, EXIF and XMP. It's the user's own zero-knowledge encrypted library. | None |
| **Chat** | **Always stripped** (`PayloadBundleEncryptionService.scrubImageMetadata`, #1297), so recipients don't get the sender's location or camera serial | None |
| **Moments** | Unchanged: the per-image location selector decides whether GPS travels, in the `MediaInfo` sidecar | None |

**One API, one required parameter.** The same pipeline serves chat and Photos, so the caller says what happens to the final image's metadata. The parameter is something like `metadata = Keep` or `Strip`.

- **Required, with no default.** Every caller has to choose. A default would either leak a sender's location in a new chat feature, or silently lose data in a new Photos feature.
- **It covers both kinds of final image.** A re-encoded image gets the source's metadata copied in (`Keep`) or gets none (`Strip`). A source file kept as it is is uploaded untouched (`Keep`) or goes through the scrubber (`Strip`, `ImageMetadataScrubber`). Today chat's scrub runs separately at upload (`PayloadBundleEncryptionService.scrubImageMetadata`). The parameter makes the pipeline the one place that decides, and the upload step stays as a safety net for chat.
- **Thumbnails and the tiny always strip,** whatever the parameter says.
- **Who passes what:**

  | Caller | Parameter |
  |---|---|
  | Photobase | `Keep` |
  | Chat, the share sheet and view-once | `Strip` |
  | Moments | `Strip` on the file; its location selector controls the `MediaInfo` sidecar as today |
  | Vault | `Strip`, as today: Vault uploads go through the same upload scrub as chat. Whether it should keep is open |
  | Everything else (avatars, link previews, contacts, stickers) | `Strip` |

When `Keep` applies to a re-encoded image, the pipeline has to copy the metadata itself, because neither Android's nor Skia's encoders carry it over:

- copy the source's EXIF, XMP and GPS across;
- set Orientation to 1, because the pixels are already upright;
- update the pixel-size tags;
- never copy the camera's embedded EXIF thumbnail.

HEIC keeps its metadata in HEIF items rather than JPEG segments. Read it from there (ImageIO properties on iOS, `ExifInterface` on Android).

## Formats

- **Thumbnails and the tiny are WebP.** It's the best size for the quality among the formats every client can decode.
- **Re-encoded final images are WebP.** A kept source stays JPEG, PNG or WebP, as it was.
- **HEIC is never uploaded as a final image on Standard or HD,** because browsers can't decode it. Original uploads the file as it is.

## Where each caller lands

| Caller | Rows |
|---|---|
| Photobase backup | Original's rows; the file is uploaded untouched |
| Chat photos, Moments, share sheet, view-once | The sender's setting |
| Group avatars and other incidental images through `MessageAttachmentBuilder` | Standard |
| Stickers | The sender's setting for thumbnails; the file stays the final image, and no quality is recorded |
| PDF previews, audio, Vault, contacts, video posters, saved stickers | Original's rows, as thumbnails only |
| Link and location previews | Tiny only, made with `createTinyPreview` |
| Profile avatars | Their own sizes and budgets, plus the shared tiny rule |

## Showing the images

- **Picking a thumbnail.** `selectThumbSize` (`HomebaseImageData.kt`) picks the smallest thumbnail whose long edge covers the displayed size, or the largest one if none does. It compares long edges, the same as generation.
- **Stepping up to the photo.** Once the thumbnail is showing, a bubble can load the final image when the display is more than 10% larger than the largest thumbnail. Without this, a 2× monitor stretches a 1080 px thumbnail. The step-up:
  - waits about 600 ms after scrolling stops, so a fling doesn't download everything it passes;
  - only loads JPEG, PNG or WebP payloads under a size limit, never a large Original;
  - only steps up when the final image is actually larger than the largest thumbnail. The final image's size is recorded in the descriptor for this;
  - is remembered, so scrolling back doesn't download again, and a failure is remembered for two minutes, so a dropped connection doesn't keep a photo soft for good;
  - is opted into per surface, such as chat bubbles, not applied everywhere;
  - on failure, keeps the thumbnail on screen without flashing an error.
- **Photobase's album grid always uses Small,** and never steps up. The album in chat-kmp is meant to stay simple; a separate app handles fast album viewing.
- **The album grid loads what you stop on first.** While the grid moves, a tile that isn't already in memory shows only its tiny and starts no download (`HomebaseImage`'s `deferLoad`). About 150 ms after it stops, the tiles in view start. The background prefetch of the newest 500 waits out each scroll.
- **On the Photos drive, a thumbnail download nobody waits for is cancelled.** `DriveFileProviderCached` shares one download per thumbnail between everyone asking for it. Photos' `DedicatedThumbCache` sets `cancelWhenUnwatched`, so the download stops when the last of them leaves, and a tile scrolled off the screen stops queueing ahead of the ones on it. Every other drive keeps the download going, so a contact row's avatar still lands in the cache after it scrolls away.
- **The tiny** is decoded off the main thread and kept in memory, so the placeholder is there on the first frame.

## Wire format and older clients

- `MediaQuality` has three values, and the codes are what's stored and sent:

  | Value | Code |
  |---|---|
  | `STANDARD` | none recorded |
  | `HD` | `"hd"` |
  | `ORIGINAL` | `"high"` |

  Original keeps `"high"` because every existing `"high"` already means "the exact file". Saved "High" settings become Original.
- **Older clients** read `"hd"` as null, through `coerceInputValues`, and show no badge. A test proves it, because a failure there would lose the whole descriptor.
- **New descriptor fields** in `ImageFile` (descriptor content), such as the final image's pixel size, are safe: older clients have `ignoreUnknownKeys`.
- **Badges:** Original shows "Original", HD shows "HD", and Standard or missing values show nothing.
- **Existing messages are never regenerated.** Any thumbnail size is readable by every client, so new sizes don't need a migration.

## Video

| Setting | Video |
|---|---|
| Standard | 720p transcode |
| HD | 1080p transcode |
| Original | Untouched |

Metadata follows the same rule as photos. Photos keeps it, and chat strips it without re-encoding, for example with a remux using `-map_metadata -1` that also removes the location atom.

## How others do it

| App | Standard | High | Original |
|---|---|---|---|
| WhatsApp | about 1600 px | HD: 4096 px, still compressed | Send as a document |
| Signal | 1600 px | High: 4096 px | Send as a file |
| Telegram | 2560 px in current clients | – | Send as a file |
| Google Photos | Storage saver: 16 MP, compressed; video 1080p | – | Original quality |
| Immich | Grid thumbnail: 250 px **short edge**, WebP q80 | Preview: 1440 px **short edge**, JPEG q80 | Original |
| **chat-kmp** | **2048 px, WebP q60** | **4096 px, WebP q80** | **Untouched** |

None of them caps the final photo by bytes; they all use pixels and quality. Our Standard is larger than the messengers' 1600 px, on purpose, for readable screenshots. Immich's short-edge sizing is what gave us the minimum short edge. Its 250 px short edge is about the same as our 320 px long edge on a 4:3 photo (333×250 against 320×240), so it didn't change our grid size.

## Testing

What must stay proven, and where.

### Planner: sizes and qualities, pure math, JVM

- Every [worked example](#worked-examples) is an exact test, in all three settings, including the band edges: 1200 against 1201, 2275 against 2276, and 4551 against 4552 on HD.
- Small sources never upscale: 600×450, 200×150, 20×15 and 1×1, in every setting.
- A random-size property test, over 5,000 sizes for each setting, checks the [properties](#working-out-the-sizes) above. It also checks that no two thumbnails are within 90% of each other, and that the final image's quality is at least that of any thumbnail within 90% of it.
- Portrait and landscape give the same sizes, transposed.

### Pipeline contract: real decoders, scalers and encoders on every platform

The pipeline contract tests are in `homebase-api/src/imageContractTest/kotlin/`. That folder is compiled into the JVM, iOS simulator and web tests, which CI runs, and into Android device tests, which CI doesn't run. **Run them on a real iPhone and Android phone before calling phone work done.**

- **Aliasing:** 1-pixel stripes turn grey at 10×, 3.94× (8064 → 2048), 2.93× (6000 → 2048), 1.97×, 1.90×, 1.69×, 1.50× and 1.46× (standard deviation ≤ 5), and at 1.25× (≤ 30). There are two variants: one where the pipeline's scaler does the work, which forces a full-size decode first, and one where the decoder scales, which on iOS means ImageIO. On iOS, a 48 MP source also goes through the memory guard: ≤ 5 at 2048, and ≤ 30 at HD's 4096, where the guard forces ImageIO's 1.43× step.
- **Sharpness:** 8-pixel stripes keep their contrast (min ≤ 20, max ≥ 235). A slight blur scores about 82–173, so blurring everything can't pass.
- **No resampling at source size:** a PNG source rendered at its own size is pixel-identical to the source.
- **Rendered sizes equal planned sizes,** so no platform's decoder shifts a size by a pixel.
- **One read and one decode per image,** shown by a test or log.
- **The tiny** fits 768 bytes for transparent noise, and carries no colour profile.
- **The 1 MiB limit:** encode with no limit first, then set the limit between byte counts. An over-limit thumbnail must come out smaller at the same quality, and a band-enlarged one at its row size.
- **Images:**
  - all 8 EXIF orientations;
  - P3 colour survives;
  - transparency survives;
  - a corrupt body throws or decodes, never comes out blank;
  - a very thin image never goes below 1 px.
- **Metadata:** a Photos final image keeps GPS, capture time and camera model with Orientation 1, and the chat path has none.

### Running them

```bash
./gradlew :homebase-api:jvmTest --tests '*ImagePipeline*' --tests '*Resampling*' --rerun
./gradlew :homebase-api:iosSimulatorArm64Test          # macOS only; also runs in CI
./gradlew :homebase-api:wasmJsTest                     # also runs in CI
./gradlew :homebase-api:connectedAndroidDeviceTest     # booted device; CI doesn't run it, so use a real phone
```

### On a real phone

Record results in `OPTIMIZE.md`:

- **Time per photo, peak memory and battery,** for 12 MP and 48 MP HEIC and JPEG, on a real iPhone and Android phone, before and after any change.
- **Real photos compared by bytes and by eye:** faces, night shots, and 12, 24 and 48 MP.
- **A small-text screenshot,** readable on Standard on a phone and on a desktop monitor at 1× and 2×.

### Reproducing the reference numbers

The Lanczos and box rows in the scaling table come from Pillow (run it with `python3 -I`):

```python
from PIL import Image
import statistics as st

def stripes(w, h, half):
    im = Image.new('L', (w, h))
    im.putdata([0 if (x // half) % 2 == 0 else 255 for y in range(h) for x in range(w)])
    return im

for src, dst in [(4032, 2048), (1080, 640), (3000, 2048), (2560, 2048)]:
    im = stripes(src, 8, 1)
    for name, f in [('box', Image.BOX), ('lanczos', Image.LANCZOS)]:
        row = [im.resize((dst, 4), f).getpixel((x, 2)) for x in range(dst)]
        print(src, dst, name, round(st.pstdev(row), 1))
```

## Decisions we made, and the alternatives we rejected

| Question | Decision | Rejected, and why |
|---|---|---|
| Which platforms to optimise for? | iOS and Android, where the library syncs from | Equal effort everywhere: desktop and web make few images. |
| How many settings? | Three: Standard, HD, Original | Two forced a choice between small and the exact file, and the old "High" was mislabelled. |
| What size for Standard's photo? | 2048 px at q60 | 1600 at q85: screenshots were unreadable, and 2048 q60 is smaller for the same look. |
| Which edge to size by? | Long edge, plus a minimum short edge and a longest edge allowed | Long edge only gives slivers for odd shapes. Short edge only, like Immich, makes sizes unpredictable. |
| How to control bytes? | Fixed quality per row and setting; only the server's hard limits | Byte budgets: being 1% over cost half the quality. |
| Upscale small sources? | Never | It adds no detail and costs bytes, and the image ends up softer. |
| Scale by 2–10%? | No: make it at the source size | Every pixel gets resampled to save very few of them. |
| Over 1 MiB? | Shrink at the same quality | Dropping to q40: visible blockiness, when a full-size photo exists anyway. |
| Different limits per setting? | No | The limits are server ceilings, and Standard never reaches them. |
| How to resample? | Halve, then a Lanczos-class filter | Plain bilinear, all Skia modes, and area averaging: all measured to alias. |
| Native code on phones? | Wherever it's measurably better, preferring platform APIs and proven libraries | Skia everywhere: no good resampler. Our own C code: libraries already exist. |
| Decode per size, or once? | Once, then chain from largest to smallest | Decoding per size meant 11 HEIC conversions per photo in the old code. |
| Keep metadata? | Photos keeps everything; chat strips; Moments asks | Stripping everywhere loses the user's own library data. Keeping it everywhere leaks the sender's location in chat. |
| Colour profile on sRGB images? | None | 456 bytes per image for no visible gain, and over half the tiny's budget. |
| Byte cap on the final photo? | None, like every other app | It would cost detailed photos their quality. |

## To do, and what isn't settled

### Not built yet

| What | Issue |
|---|---|
| Phone measurements of the native resamplers against the shared Kotlin Lanczos, recorded in `OPTIMIZE.md` | #2301 |
| iOS: hand pixels from ImageIO over without extra copies | #2236 |
| iOS: check ImageIO's sizes match the plan for odd shapes | #2235 |
| A required keep-or-strip metadata parameter on the image API, covering re-encoded and kept final images; Photos passes `Keep` | #2235 |
| Original video untouched, with chat's copy stripped by a remux | #2235 |
| Time, memory and battery measured on a real iPhone and Android phone | #2221, #2236 |
| RAW: thumbnails from the embedded preview when it's large enough, rendering otherwise | – |
| RAW: verify on real phones which formats decode (ProRAW and DNG on iOS; DNG on Android) | – |

### Not settled

- **Whether vImage beats `stb_image_resize2` on iOS.** vImage ships now because it's a system library; swap only if the phone measurements say so.
- **Reading from the file instead of a byte array** on phones (`ImageDecoder.createSource(File)`, `CGImageSourceCreateWithURL`), so the encoded file isn't held in memory as well. Measure whether it's worth it for Photobase.
- **Does Photos offer HD?** Today it backs up only the untouched file, and an HD backup would need `BackupRequestBuilder.kt` changed, because it requires the uploaded bytes to match the device file's hash.
- **Final qualities.** q60 and q70 are pending the real-photo test, especially on faces.
- **Judgement calls to check on real photos:** the 10% band, the q60 floor and the shrink estimate follow common practice. Adjust them if real photos say otherwise.
- **RAW+JPEG pairs on iOS.** Back up the RAW too, as part of the same photo? Today it's skipped. Android backs up both as separate photos; should they be linked?
- **GIFs in Photos' grid.** Give GIFs still thumbnails from the first frame, so the grid doesn't download whole GIFs, while bubbles keep playing the file? Older clients would ignore the thumbnails and still play the file, so it's safe.
- **Animated WebP in bubbles.** It shows still today. Animating it needs a descriptor flag, because its type is the same `image/webp` as a still image.
- **Should Vault keep metadata?** It's the user's own encrypted storage, like Photos, but today it strips through the shared upload scrub.
- **Linear-light resampling.** Not used; see [How to resize well](#how-to-resize-well). Revisit only if a real-photo test shows a visible gain.
