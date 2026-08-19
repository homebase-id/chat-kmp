# Email as an add-on app

Status: design/plan — not yet implemented. Part 3 of the email design chain:
odin-core `docs/email-dns-plan.md` (DNS/discovery) → odin-core `docs/email-keys-plan.md`
(keys, Stalwart, activation) → this doc (the client). Follows `ADDING_ADDON_APPS.md`
conventions throughout — Email is the next add-on app after Vault.

## Add-on framing (the Vault pattern)

Email installs only if the user wants it:

- Toggleable icon in the bottom nav / side rail; **onboarding screen** on first tap
  with *Set it up* / *Dismiss*.
- Activation is **derived from the email drive's existence** — the same
  no-local-`activated`-flag rule Vault/Moments/Location/Stickers follow, and the same
  indicator the server's monthly check and RCPT-time policy key off (see the odin-core
  docs). One source of truth for "email is on", client and server alike.
- Settings sub-page: hide icon, **biometrics switch** (recommended on by default —
  mail is at least as sensitive as Vault), signature, notification preferences.
- Standard anatomy applies (`EmailScreen`/`EmailContent`/`EmailViewModel`/`EmailUiState`,
  preferences in `homebase-common`, fresh UUID keys, `EmailBiometricGate`).

**The one structural novelty**: Email is the first add-on whose *data plane is not a
drive*. Mail lives in Stalwart and is read over **JMAP** (decision below); the email
drive holds only key material (and locally-authored state like drafts, if we want them
synced). Expect the service layer to look different from Vault's
`OutboxSync`/`OptimisticWriter` shape — `EmailService` wraps a JMAP client instead.

## Setup flow (what *Set it up* does)

1. Extend-permissions dialog (standard add-on step) — includes the email drive.
2. Create the **email drive**.
3. Generate the **E2E encryption keypair on the device**, store it encrypted on the
   email drive (⇒ Shamir-recoverable, multi-device via drive sync — see the keys doc's
   custody table; the private key never exists outside owner-locked storage).
4. Choose the **email address** (see "Addressing" below).
5. Call the server's `POST /api/owner/v1/mail/activate` — the server does the rest
   (DKIM generation, DNS records, DID/WKD publication, Stalwart provisioning; keys doc).
6. On completion the app shows the address as live; DNS-dependent bits (delegated vs
   manual-records domains) may surface a "records pending" state fed by the owner
   API's status endpoints.

Deactivation/uninstall hides the app but deletes nothing; actual teardown is a
deliberate, separate destructive action (mail + keys are on the line).

## Mail access: JMAP-direct (decision)

The app reads and manages mail via **JMAP against Stalwart** (through the identity
host). Stalwart is the single source of truth, so Thunderbird-class clients and the
app always agree on state (read/flags/folders).

- **Read path**: JMAP fetch → blobs are ciphertext (encryption-at-rest) → decrypt
  client-side with the drive key → render. Nothing decrypted ever goes back up.
- **Send path**: compose → for Homebase recipients, fetch their public key (DID
  `keyAgreement`/WKD via the recipient's identity) and encrypt+sign **client-side**
  (true E2E; the OpenPGP signature is the *person* vouching — DKIM, added server-side
  by Stalwart, is the *domain* vouching; two signatures, two signers, see the keys
  doc) → submit; external recipients get plaintext submission (relay-visible,
  inherently).
- **Search**: server-side search over bodies is impossible by design (ciphertext), so
  search is a **client-side index** in the encrypted local store, built as messages
  are decrypted. Headers/metadata queries can still use JMAP.
- **Offline**: JMAP client keeps a local cache in the encrypted store (bounded,
  LRU-ish); this is deliberately lighter than drive sync.
- **Push**: the server hooks inbound delivery into the existing notification system →
  standard push → app badge/refresh. No JMAP polling.

*Explicitly deferred alternative*: mirroring mail onto the email drive (drive-native
reading, full offline via existing sync machinery). It stays open as a later layer —
Stalwart remains authoritative regardless — but the outline starts with one store.

## Addressing (decision this doc forces)

**One mailbox per identity**, address derived from the domain:

- **Managed domains**: `first-label@rest` — `john.doe.id.pub` → `john@doe.id.pub`
  (the provisioning app's `splitMailFromPrefixAndApex` helper already implements
  exactly this split).
- **BYOD**: the user picks a localpart at setup (default suggestion e.g. `me` or the
  domain's first label); `gabriel.ninja` → `<localpart>@gabriel.ninja`.
- Aliases / additional mailboxes: out of scope now, nothing prevents them later
  (WKD/Stalwart both handle multiple localparts).

## Key UX

- **Health**: the app can run the same encrypt/decrypt round-trip the owner-console
  Email tab runs (it holds the drive key) — surfaced as a quiet indicator, loud only
  on failure (which means incoming mail is being encrypted to a key this device
  cannot decrypt — critical, see the keys doc).
- **Rotation**: an explicit settings action → new keypair appended to the drive
  (old keys never deleted; old mail stays readable) → re-run activate. Rare,
  deliberate, confirmed.
- No key material in app preferences/local store beyond the session-unlocked copy;
  the drive is the home.

## Out of scope

- Webmail and legacy-client onboarding UI (autoconfig/app passwords are server-side;
  a settings page may later link to "connect Thunderbird" instructions).
- Drive-mirrored mail (deferred alternative above).
- Spam handling UX, mailing-list features, multiple accounts.
- The JMAP client library choice (evaluate at implementation; scope it to what the
  screens need, not the whole RFC).
