# Email as an add-on app (setup & key control panel)

Status: design/plan — not yet implemented. Part 3 of the email design chain:
odin-core `docs/email-dns-plan.md` (DNS/discovery) → odin-core `docs/email-keys-plan.md`
(keys, Stalwart, activation) → this doc (the client). Follows `ADDING_ADDON_APPS.md`
conventions — Email is the next add-on app after Vault.

**What this app is — and is not.** It is the place where you *set up and manage* your
email: activate it, manage the names your mailbox answers to, manage your keys, and
connect real mail clients. It is **not a mail client** — reading and composing happen
in standard clients (Thunderbird, FairEmail, Apple Mail, …) talking IMAP/JMAP to
Stalwart directly. An in-app reader may come later; nothing here precludes it.

## Add-on framing (the Vault pattern)

- Toggleable icon in the bottom nav / side rail; **onboarding screen** on first tap
  with *Set it up* / *Dismiss*.
- Activation is **derived from the email drive's existence** — the same
  no-local-`activated`-flag rule Vault/Moments/Location/Stickers follow, and the same
  indicator the server's monthly check and RCPT-time policy key off. One source of
  truth for "email is on", client and server alike.
- Settings sub-page: hide icon, **biometrics switch** (recommended on by default —
  this app can export the private key), notification preferences.
- Standard anatomy applies (`EmailScreen`/`EmailContent`/`EmailViewModel`/`EmailUiState`,
  preferences in `homebase-common`, fresh UUID keys, `EmailBiometricGate`). Being a
  settings/control surface, there is no `EmailStream`/upload machinery — the service
  layer is thin calls to the owner API.

## Setup flow (what *Set it up* does)

1. Extend-permissions dialog (standard add-on step) — includes the email drive.
2. Create the **email drive**.
3. Generate the **E2E encryption keypair on the device**, store it encrypted on the
   email drive (⇒ Shamir-recoverable, multi-device via drive sync; the private key
   never exists outside owner-locked storage — keys doc custody table).
4. Choose the **primary address** (below).
5. Call the server's `POST /api/owner/v1/mail/activate` — the server does the rest
   (DKIM generation, DNS records, DID/WKD publication, Stalwart provisioning).
6. Show the address as live; delegated vs manual-records domains may surface a
   "records pending" state fed by the owner API status endpoints.

Deactivation/uninstall hides the app but deletes nothing; teardown is a separate,
deliberately heavy destructive action (mail and keys are on the line).

## Addresses: many names, one box

The mailbox answers to multiple names — all landing in the same box:

- **Primary address**: managed domains derive it — `john.doe.id.pub` →
  `john@doe.id.pub` (the provisioning app's `splitMailFromPrefixAndApex` already
  computes this split); BYOD users pick a localpart at setup.
- **Aliases**: add/remove additional localparts in the app. Server-side, each alias
  is provisioned into Stalwart (delivery to the one account) and published in **WKD**
  (per-localpart hash, same key — one box, one keypair). The wrapper gains
  `SetAliasesAsync(domain, localparts)` (keys doc).
- One account, one keypair, one drive — aliases are names, not mailboxes.

## Keys

- **Health**: the app runs the same encrypt/decrypt round-trip as the owner-console
  Email tab (it holds the drive key) — quiet indicator, loud only on failure (which
  means incoming mail is being encrypted to a key this device cannot decrypt —
  critical; keys doc).
- **Rotation**: explicit action → new keypair appended to the drive (old keys never
  deleted; old mail stays readable) → re-run activate. Rare, deliberate, confirmed.
- **Private key export** — the reason external clients can read encrypted mail:
  Thunderbird-class clients need the OpenPGP private key in their own keyring to
  decrypt (mail is stored encrypted at rest; Stalwart serves ciphertext). The app
  exports the key as a **passphrase-protected OpenPGP file/QR**, behind the biometric
  gate, with an explicit warning that the key's protection now extends to that
  device. Export is the user's deliberate act — the server never does this.

## Connect a mail client (the Thunderbird flow)

One guided screen bundling everything an external client needs:

1. **App password** — generated here via the owner API, provisioned into Stalwart
   (wrapper), shown once, revocable from the same screen (list of issued passwords).
2. **Autoconfig** — nothing to do: the server publishes
   `.well-known/autoconfig/mail/config-v1.1.xml`, so Thunderbird self-configures from
   the email address; the screen just says so and shows the manual IMAP/SMTP values
   as fallback.
3. **Private key import** — the export above, with short per-client instructions
   (Thunderbird: Account Settings → End-to-End Encryption → import).

## Out of scope (now)

- Reading/composing mail in the app — and therefore the whole JMAP-client/offline/
  search/drive-mirror question. If an in-app reader comes later, that debate reopens
  with this control panel unchanged.
- Webmail.
- Spam handling UX, mailing-list features, multiple accounts/mailboxes (aliases cover
  the "multiple names" need).
