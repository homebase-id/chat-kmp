# Desktop FFmpeg bundle (#1035)

Pinned to the **FFmpeg 7.1 release line** on every desktop platform. Before this
the bundle was fragmented — gyan git-snapshot on Windows, johnvansickle 7.0.2 on
Linux, two *different* martin-riedl git-master snapshots on the two macOS arches —
which made Desktop transcode output non-reproducible across platforms (output
differs by ffmpeg version; see `FFMPEG_COMPRESSION_NOTES.md`).

| Platform | Source | Reported version |
|---|---|---|
| windows-x64 | [BtbN FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds), gpl | `n7.1.5-1-g7d0e842004-20260709` |
| linux-x64 | BtbN, gpl | `n7.1.5-1-g7d0e842004-20260709` |
| linux-arm64 | BtbN, gpl | `n7.1.5-1-g7d0e842004-20260709` |
| macos-arm64 | [OSXExperts](https://www.osxexperts.net) | `7.1` |
| macos-x64 | OSXExperts | `7.1` |

The three BtbN arches are the same 7.1.5 build; both macOS arches are the same
7.1.0 build. All are stable release-channel, not git-master snapshots.

## Refreshing

```sh
./refetch.sh          # downloads the pinned sources, swaps the binaries, rewrites SHA256SUMS
```

`SHA256SUMS` pins the exact bytes — the BtbN `latest`-tag URLs track the newest
7.1.x, so the checksums (committed) are the real reproducibility anchor. Re-run
and commit the updated binaries + `SHA256SUMS` together.

## Caveats (need a maintainer call)

- **App size**: BtbN's GPL builds are ~40 MB/binary heavier than the old lean
  gyan/johnvansickle builds → the bundle is 966 MB (was 736 MB), +~230 MB in the
  desktop distributable. If size matters more than a single distributor, swap
  Windows to gyan's leaner `essentials` 7.1 build.
- **git storage**: these ~966 MB are tracked as **raw git blobs, not LFS**.
  Every re-bundle adds the new blobs to history permanently. Migrating this path
  to git-LFS before the next swap would stop the bloat.
