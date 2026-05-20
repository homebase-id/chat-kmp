# Moments — framing context

Reopen this branch (`moments-framing`) and start here. This is a scaffold + UI
framing pass for a new add-on app called **Moments** ("moments worth
remembering"), following [`ADDING_ADDON_APPS.md`](ADDING_ADDON_APPS.md).

## What's done

### Add-on scaffold (real, not skeleton)

Pattern: real `ExtendPermissionDialog` + qualified `ExtendPermissionViewModel`,
**not** the doc's simplified inline-dialog version (the inline version doesn't
actually request server-side permission extension).

| Concern | Decision |
| --- | --- |
| Drive alias | `a85f8562-6c74-4947-896b-619812cafccc` |
| Drive type | `4338d7d2-f217-486a-8790-a4982644c15f` |
| Icon | `Icons.Outlined.AutoAwesome` |
| Biometric gate | **No** — skipped Step 6/11 of the doc |
| UUID namespace for prefs | `0a02xx` (Vault owns `0a01xx`) |

New files:

- `homebase-common/src/commonMain/kotlin/id/homebase/core/moments/MomentsPreferences.kt`
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/moments/`
  - `MomentsViewModel.kt` + `MomentsUiState.kt`
  - `MomentsOnboardingScreen.kt`
  - `MomentsScreen.kt` *(skeleton — Page 1)*
  - `MomentDetailScreen.kt` *(skeleton — Page 2)*
  - `MomentCreateScreen.kt` *(skeleton — Page 3)*
  - `MomentsSettingsScreen.kt` + `MomentsSettingsViewModel.kt` + `MomentsSettingsUiState.kt`

Edited files:

- `AppConfig.kt` — `momentsLabeledDrive`, `momentsTargetDriveAccessRequest`, `getMomentsPermissionExtensionConfig()`
- `Routes.kt` — `Moments`, `MomentsOnboarding`, `MomentsSettings`, `MomentDetail(momentId)`, `MomentCreate`
- `strings.xml` — `moments_*` keys (under the *Moments add-on* and *Send to* / *detail* blocks)
- `AppModule.kt` — `MomentsPermissionQualifier`, `single { MomentsPreferences }`, qualified `ExtendPermissionViewModel`, `viewModel { MomentsViewModel(...) }`, `viewModelOf(::MomentsSettingsViewModel)`
- `AppNavHost.kt` — `TopLevelRoute.Moments`, reactive `topLevelRoutes`, `openMoments`, five Moments composables, event collector, `isTopLevelRoute()` updated
- `SettingsScreen.kt` — `onNavigateToMomentsSettings` callback + AutoAwesome row
- `ApiModule.kt` — `factoryOf(::IdentityUpgradeProvider)`
- `ADDING_ADDON_APPS.md` — two new gotchas about the `setupInitiated` gate and `Dismissed` observer

### IdentityUpgradeProvider (built, NOT wired)

`homebase-api/src/commonMain/kotlin/id/homebase/api/client/upgrade/IdentityUpgradeProvider.kt`
— calls `GET /api/v2/auth/verify-token`, returns `true` if response header
`X-REQUIRES-UPGRADE` is present (presence is the signal, not the value). Already
registered in `ApiModule.kt`, ready to inject.

**Not yet hooked into the Moments setup flow.** See *Open question* below.

## Two non-obvious behaviours we hit

Both are documented as gotchas in `ADDING_ADDON_APPS.md`, but worth re-stating:

1. **`ExtendPermissionDialog` auto-prompts unless gated.**
   `ExtendPermissionViewModel.init` runs `checkPermissions()` eagerly. As soon as
   `MomentsViewModel` is constructed (which `AppNavHost` does at the top of
   composition via `koinViewModel()`), the qualified permission VM checks the
   server, sees the new drive isn't granted, and flips its state to `ShowDialog`.
   Solved by `setupInitiated: Boolean` in `MomentsUiState` — only flipped on
   *Set it up* tap. Both the dialog render and the `ON_RESUME` recheck observer
   are gated behind it.

2. **Cancelling the dialog must reset `setupInitiated`.** Otherwise the next
   visit to onboarding fires the `ON_RESUME` observer → `recheckPermissions()` →
   `ShowDialog` again, re-prompting a user who already said no.
   `MomentsViewModel.init` observes
   `momentsPermissionViewModel.uiState.filter { it is Dismissed }` and resets
   the flag. Catches Cancel button, tap-outside, **and** owner-console
   `PermissionsExtensionCanceled`.

## Skeleton pages — what each one needs next

The three feed pages are placeholder UIs with hardcoded sample data. Each is
shaped according to the spec but the visual cells are `surfaceContainerHighest`
swatches, not real components. The user has existing components to plug into
each one.

### Page 1 — `MomentsScreen.kt` (feed)

- `LazyColumn` of post cards, each a 2×2 grid teaser with overlay indicators
  (Info badge if `hasDescription`, ChatBubble if `commentCount > 0`, heart for
  reaction).
- FAB → `Route.MomentCreate`.
- Tap card → `Route.MomentDetail(id)`.
- **Plug in:** real asset/image cells in `AssetGridTeaser`, real post data
  source (drive sync feed). Probably needs a `MomentsViewModel` for the feed
  list (currently only the onboarding VM exists).

### Page 2 — `MomentDetailScreen.kt`

- `HorizontalPager` (one page per asset, square aspect ratio) with pager dots
  overlaid bottom-center.
- Reactions row (heart `AssistChip` + 4 emoji chips).
- Description (falls back to *No description added*).
- Metadata rows (`Captured`, `Device`).
- Comments section **gated on `commentsEnabled`** — header, list of comments
  (avatar + author + text + likes + heart button), empty state, and a composer
  (`OutlinedTextField` + send button).
- **Plug in:** real media (image/video), real comment list + send handler,
  real metadata source.

### Page 3 — `MomentCreateScreen.kt` (audience selector)

- Search field (state only, no filtering yet).
- Sections: **Recent** (MRU), **Quick options** (*New group*, *One-off* —
  navigate elsewhere via `onCreateNewGroup` / `onOneOff` callbacks, currently
  unwired), **Groups**, **Contacts**.
- Multi-select (`Set<String>` of recipient ids in `remember`).
- Chat groups get a small heart + *Chat group* label under the name.
- Bottom bar: *N selected* count + **Next** button (disabled when empty).
- **Plug in:** real groups + contacts source, MRU computation, *New group* /
  *One-off* destinations, **the composer (Page 4) that *Next* should navigate
  to**.

## Open question — upgrade detection

User identities sometimes need a server-side data-version upgrade before new
system drives (like Moments) exist. Detection is a one-line check via
`IdentityUpgradeProvider.isUpgradeRequired()`. The wrinkle is the UX:

- Upgrade and extend-permissions are **two different operations against two
  different URLs** in the owner console. We confirmed they're independent —
  permissions can need extending even when no upgrade is required.
- A pre-check on *Set it up* would route the user to `https://{identity}/owner`
  for the upgrade first, then on `ON_RESUME` recheck once the upgrade is done,
  then trigger the existing `ExtendPermissionDialog` for the second trip
  (permission grant). Two browser visits, but each clean.
- Alternatives we discussed and rejected: tweaking the dialog copy (doesn't
  work — the URL is wrong); a global app-wide upgrade banner at login
  (decoupled but maybe too broad).

**Status:** discussion paused. `IdentityUpgradeProvider` is ready to call but
isn't wired anywhere yet. When we resume, the likely shape is a dedicated
"Upgrade required" state in `MomentsViewModel` that auto-advances on
`ON_RESUME` recheck, plus a fallback *I've completed the upgrade* button.

## What still needs ViewModels

The three feed pages are stateless skeletons. As we replace placeholders with
real data, each will likely need its own VM:

- Feed list VM (post stream, FAB nav, tap nav)
- Detail VM (single post fetch, comment send, reaction toggle)
- Create VM (recipient list source, MRU, multi-select state, Next handler)

The existing `MomentsViewModel` is *only* for onboarding/activation — keep it
narrow.

## Build sanity

```bash
./gradlew homebase-core:compileKotlinJvm           # fastest sanity check
./gradlew desktopApp:compileKotlinJvm androidApp:compileDebugKotlin
```

All three compile clean as of the last commit on `moments-framing`. UI hasn't
been visually tested in a running app yet — only compile-verified.
