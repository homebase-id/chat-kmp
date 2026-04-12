# Release Guide

This guide explains how to release test and production builds to App Store Connect TestFlight and Google Play Internal test track.

**Note:** Full production releases require manual sign-in to app stores and manual promotion.

---

## Version Numbering

Update version information in `gradle/version.properties`.

**Important:** Desktop production releases require incrementing the version name between each release.

---

## Mobile Apps

### Test Build (`id.homebase.feed.dev`)

**Distribution:** TestFlight (iOS) & Google Play Internal Testing (Android)

**Deployment Schedule:**
- Nightly (automated)
- Manual trigger available

**GitHub Action:** `Build Mobile Release Dev`

---

### Production Build (`id.homebase.feed`)

**Distribution:** TestFlight (iOS) & Google Play Internal Testing (Android)

**Deployment Schedule:** Manual trigger only

**GitHub Action:** `Build Mobile Release Production`

---

## Desktop Apps

### Test Build (`id.homebase.feed.dev`)

**Deployment Schedule:**
- Nightly (automated)
- Manual trigger available

**GitHub Action:** `Build Desktop Release Dev`

---

### Production Build (`id.homebase.feed`)

**Status:** 🚧 Coming soon

---

## Quick Reference

| Platform | Variant | App ID | Action |
|----------|---------|--------|--------|
| Mobile | Dev | `id.homebase.feed.dev` | Build Mobile Release Dev |
| Mobile | Production | `id.homebase.feed` | Build Mobile Release Production |
| Desktop | Dev | `id.homebase.feed.dev` | Build Desktop Dev Release |
| Desktop | Production | `id.homebase.feed` | _Coming soon_ |