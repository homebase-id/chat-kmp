# Native Feed — Backend (v2) requirements

The native feed (PR #802) is complete and shippable for the **owner's own feed**:
viewing, posting, editing, deleting, reposting, comments (text + image + sticker),
reactions, channels, and following all work end-to-end on the v2 bearer-token API.

Three capabilities remain blocked on **backend v2** work. Each is an over-peer or
followers concern that the unified v2 API does not yet expose to a bearer token.
This PR tracks those asks; no client change unblocks them.

## 1. Followers / following lists (currently 404)

`FollowProvider` needs the follower/following lists for the "Following" screen.

- The v2 path the client would use (`/api/v2/followers/*`) **does not exist**.
- Only the **classic** routes exist: `/api/{apps|owner|guest}/v1/followers`,
  which authenticate a classic app **cookie** token — the chat-kmp **v2 bearer
  token gets 401** there (the route resolves; it is an auth-scheme mismatch, not a 404).

**Ask:** a v2 followers list endpoint under `UnifiedV2Authorize(OwnerOrApp)` that
returns the identities the owner follows / is followed by (bearer-token auth).

Until then the Following screen shows an empty state (no error branch — by design).

## 2. Comment read on followed posts (over-peer)

Comments the client **writes** on a followed post already reach the author over
transit (fixed: recipients resolve from `originalAuthor`). But **reading** other
people's comments on a followed post does not work: those comments live on the
**post author's** drive, and `PostCommentsService.commentsFor` only queries the
local FeedDrive + channel drive.

**Ask:** an over-peer comment query (by the post's `groupId`) against the author's
drive under `OwnerOrApp` bearer — or a server-side aggregation that folds a
followed post's comments into the follower's FeedDrive reaction-preview.

## 3. Over-peer full-res media by globalTransitId

Feed-drive records for followed posts are **references**: they carry a
`globalTransitId` and a `DataSource{identity, driveId, payloadsAreRemote}` but
**no author fileId** (fileId is per-drive). To show full-res media the client must
fetch the payload/thumb from the author over peer.

- The v1 `payload_byglobaltransitid` / `thumb_byglobaltransitid` endpoints exist
  only under classic App/Owner/Guest **v1** (cookie auth) → v2 bearer **401s**.
- Unified **v2** exposes over-peer payload/thumb **only by fileId**
  (`/api/v2/peer/{odinId}/drives/{driveId}/files/{fileId}/payload/{key}[/thumb/{w}/{h}]`,
  bearer works). v2 **by-globalTransitId** is `exists`-only.
- A 2-call client path (resolve gtid→author-fileId via
  `POST /api/v2/peer/{id}/drives/{driveId}/query-batch`, then fetch by fileId) is
  possible **today**, but rejected: ~2 calls per payload × ~10 posts on screen is
  too chatty.

**Ask:** a v2 over-peer **payload/thumb by globalTransitId** controller under
`UnifiedV2Authorize(OwnerOrApp)` (port the v1 logic) → one call per payload.

Until then, followed-post media falls back to the embedded preview thumbnail that
ships in the post header (no regression; just lower-res).

---

Reference implementation for all three: dotyoucore-js
(`packages/libs/js-lib/src/public/posts`), which uses the v1 cookie routes the
chat-kmp v2 token cannot. DotYouCore backend repo: the v1 controllers to port are
`PeerQueryControllerBase` (payload/thumb by gtid) and the `v1/followers` controllers.
