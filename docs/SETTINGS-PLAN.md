# Settings architecture plan

The goal is a settings *hierarchy* driven by a reusable component framework, not
one growing page. This document records the plan and, more importantly, which
parts of the proposed structure the engine can actually honour.

Everything below was checked against the GeckoView 147 API surface
(`geckoview-147.0.20260212191108`) by inspecting the AAR, not from memory.

## Principle

**No switch is added unless something implements it.** A greyed-out or
decorative control is worse than an absent one: it teaches users that the
browser has a setting it does not have. Every entry in the table below is
marked *backed* (a real API or real shell code drives it) or *omitted* (no
surface exists, and the reason is recorded in `TESTING.md`).

## What the engine exposes

| Capability | GeckoView 147 surface | Verdict |
| --- | --- | --- |
| Enhanced Tracking Protection level | `ContentBlocking.enhancedTrackingProtectionLevel` | backed |
| Per-category tracking controls | `enhancedTrackingProtectionCategory`, `strictSocialTrackingProtection` | backed (Custom checklist is real) |
| ETP exceptions per site | none | **omitted** |
| Fingerprinting protection | `setFingerprintingProtection`, `setBaselineFingerprintingProtection`, `…Overrides` | backed (levels) |
| Canvas / WebGL / font switches individually | none (only levels + a global override string) | **omitted** |
| Cookies: block third-party / allow all / block all | `cookieBehavior`, `cookiePurging` | backed |
| Cookie banner handling | `cookieBannerHandlingMode`, per-domain via `StorageController` | backed |
| HTTPS-only / upgrade | `httpsOnlyMode` | backed (no separate HTTPS-First level) |
| Secure DNS | `trustedRecursiveResolverUri` + mode | backed |
| Per-site permissions (ask/allow/block) | `StorageController.getPermissions/setPermission` + the shell's own store | backed |
| Site storage **sizes** per origin | none | **omitted** (per-site *clear* is real) |
| Clear by time range (cookies, cache) | none | **omitted** (history is ours, so history-only ranges work) |
| JavaScript on/off | `setJavaScriptEnabled` (runtime + per session) | backed |
| Pop-ups block/allow | enforced in the shell's `onNewSession` prompt | backed |
| Autoplay ask/allow/block | shell media permission bridge (`VALUE_PROMPT/DENY/ALLOW`) | backed |
| Background media | `setSuspendMediaWhenInactive` | backed |
| Picture-in-picture, fullscreen, media notification | shell | backed |
| Passkeys / WebAuthn | none (engine-side) | **omitted** |
| Reader mode | no `readerMode` on `GeckoSessionSettings` in this build | **omitted** |
| Page zoom (per site) | no `Zoom` type in the AAR | **omitted** (global default only, via pref) |
| Font size floor | pref `font.minimum-size.x-western` | backed (experimental pref setter) |
| Force dark | pref `layout.css.prefers-color-scheme.content-override` | backed (experimental pref setter) |
| WebRTC address-only | pref `media.peerconnection.ice.default_address_only` | backed (experimental pref setter) |
| Proxy configuration | none | **omitted** (system/document boundary) |
| Toolbar position, home/share button visibility | shell layout work, not settings | deferred, listed as not built |
| Storage breakdown by category | profile size and downloads are measurable; engine cache/cookie sizes are not | partial, honestly labelled |

## Structure

```text
Settings  (root, with search)
├── Privacy & Security
│   ├── Tracking protection        level + per-category custom
│   ├── Fingerprinting             standard / strict / off
│   ├── Cookies & site data        third-party policy, cookie banners, clear
│   ├── HTTPS                      upgrade / strict
│   ├── Secure DNS                 provider, mode, fallback
│   ├── Permissions                camera, mic, location, notifications per site
│   ├── Site data                  per-site clear (no fake sizes)
│   ├── Privacy report             trackers, threats, history
│   └── Clear browsing data        independent categories
├── Search
│   ├── Search engine              add/edit/delete/default
│   └── Search suggestions         provider warnings
├── Tabs
│   ├── Tab behaviour              layout, close policy, session restore
│   └── Tab groups
├── Appearance
│   ├── Theme                      system / light / dark
│   └── New tab page               search, shortcuts, recently visited
├── Websites
│   ├── Site permissions
│   ├── JavaScript                 allow / block
│   ├── Pop-ups                    block / allow
│   ├── Autoplay                   ask / allow / block
│   └── Notifications              ask / allow / block
├── Downloads
│   └── Location, notifications, auto-open, clear history
├── Passwords & Autofill
│   └── Vault, autofill  (passkeys omitted: no embedder API)
├── Media
│   └── Autoplay, background playback, PiP, media notifications
├── Accessibility
│   └── Text scaling, reduce-motion respect
├── Data & Storage
│   └── Measured usage, cache, site data, auto-clear
├── Advanced
│   ├── Network                    DoH, WebRTC
│   ├── Web compatibility          "troubleshoot this site"
│   ├── Developer options          remote debugging (debug builds), diagnostics
│   └── Reset settings
└── About
    ├── Version, GeckoView build
    ├── Licenses
    └── Report a problem
```

Plus a **Privacy centre** on the root page: one screen that answers "what is my
privacy state right now" from data the app actually has.

## Framework

Built first, so every later page is the same four calls:

```kotlin
settingsScreen("privacy_cookies", "Cookies & site data") {
    section("Third-party cookies") {
        choice("Block third-party cookies", store.blockThirdPartyCookies,
            "Allow", "Block third-party", "Block all") { store.blockThirdPartyCookies = it }
    }
}
```

Components: `nav`, `toggle`, `choice`, `slider`, `action`, `info`. Each carries
title, subtitle, an icon and **search keywords**, and a `ScreenRegistry` indexes
all of them so the root search can find `cookies` from anywhere.

Every screen is rendered by one `SettingsActivity` from its id, so navigation,
insets, theming and back behaviour are implemented once.

## Phases

1. **Framework** — DSL, components, registry, renderer, root screen with search.
   Every existing setting keeps its current storage key and stays reachable.
2. **Privacy & Security** — the largest category, split out of the flat page.
3. **Search + Tabs.**
4. **Websites** — the engine-backed behaviour defaults.
5. **Appearance + Downloads + Media + Accessibility.**
6. **Passwords + Data & Storage + Advanced + About + Privacy centre.**
7. **Device pass** over the whole tree, then the docs.

Each phase builds, lints, is driven on a physical device, and is committed on
its own so a regression is one commit away.
