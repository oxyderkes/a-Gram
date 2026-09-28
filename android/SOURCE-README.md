# ά‑Gram source overlay

This archive contains the files changed for the local ά‑Gram `12.10.5-a-gram.26-test.6` Android update candidate. It is not a device-verified stable release. It intentionally does not contain Telegram API credentials, a private signing keystore, generated build output, Android SDK, NDK or Gradle caches.

This candidate excludes embedded Tor and HTML/PDF chat export. See [STABILITY-CHECKLIST.md](STABILITY-CHECKLIST.md) for the required regression gate before distribution and [HARDENING-26-test.5.md](HARDENING-26-test.5.md) for the isolation audit and remaining boundaries. Its base version code is `7111` (arm64 standalone APK: `71119`), greater than `.26-test.5` (`71109`). It preserves the restored single-scroll settings and Telegram theme surfaces.

The account settings interface is restored to the `.24` single-scroll card layout, before and after login. The multipage settings menu is removed. Theme-aware controls, storage/session guards, locked device profiles, asynchronous saving, unsaved-change confirmation and persistent-archive diagnostics remain. This is a UI restoration, not a downgrade of the application or account data.

This revision fixes invisible dark-theme text caused by Windows CRLF conversion. The five bundled `.attheme` assets are copied byte-for-byte from the pinned upstream commit and protected with `-text` attributes (themes can contain binary wallpaper data). The theme reader also tolerates CRLF text lines without changing binary wallpaper offsets. Do not normalize theme files as ordinary text.

When restoring these assets from upstream on Windows, disable export conversion too (`git -c core.autocrlf=false archive ...`); a plain `git archive` can apply CRLF conversion. Verify each file with `git hash-object --no-filters` against the pinned commit's blob ID, then run the theme regression checks against the final APK.

### Settings cleanup in .26-test.6

The account-settings menu is now compact: the duplicate automatic-container card is removed, technical notes and the exact Telegram session preview are expandable, and the persistent-archive card contains a deleted-message retention toggle. It defaults to enabled for new containers and preserves an existing explicit choice when migrating. The same setting remains available in Privacy; disabling it does not erase saved history. Secret/disappearing/protected content keeps its existing exclusions. These changes are not included in the previously signed `.26-test.5` APK.

Verified locally on 2026-09-28: the complete Android regression runner, eight compact-menu source contracts, retention default/migration/persistence/rollback/UUID/session tests, overlay security checks and live-theme checks all pass. Full standalone assembly succeeds (5m 59s); packaged themes, merged manifest, v2/v3 signature and 16 KB zip alignment checks pass. The signed `.26-test.6` APK was installed over `.26-test.5` on a Pixel 10 Pro XL / GrapheneOS without clearing data. The account and device profile survived, compact settings and the enabled retention switch were checked, and a cold start succeeded. This limited smoke is not the full stability gate; no destructive or multi-account scenarios were run.

## Base source

- Repository: https://github.com/DrKLO/Telegram
- Commit: `dc780e81ed1261c369c27870e8e0999a1eb0b600`
- Upstream version: `12.10.5 (7105)`; latest published source checked on 2026-09-26.
- License: GNU GPL v2 or later; see `LICENSE`.

The overlay preserves paths relative to the repository root. Copy it over a checkout of the exact commit.

The Android application ID and signing identity intentionally retain their pre-rebrand Manygram values so ά‑Gram can be installed as an in-place update without deleting local app data.

## Reconstruct and build

1. Clone the upstream repository, check out the commit above, then run `git submodule update --init --recursive`. New Media3 and native submodules must match the pinned commit; do not copy native libraries from an older Telegram build.
2. Copy every file from this overlay over the repository root.
3. Copy `local.properties.example` to `local.properties`, set `sdk.dir`, and add private production `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` values obtained from https://my.telegram.org/apps. The build now fails when these values are absent or malformed; there is no public test fallback.
4. Install JDK 17, Android SDK 35 and 36, Build Tools 35.0.0 and 36.0.0, NDK `27.2.12479018` and CMake 3.22.1. The Media3 submodules use SDK 35 while the application targets 36. Use the pinned upstream Gradle 8.13 wrapper and Android Gradle Plugin 8.13.2. Native source now requires C++17.
5. Generate a signing key and provide its passwords only through environment variables or a private user-level Gradle configuration:

   ```powershell
   $env:AGRAM_RELEASE_STORE_PASSWORD = "<strong-private-password>"
   $env:AGRAM_RELEASE_KEY_PASSWORD = "<strong-private-password>"
   $env:AGRAM_RELEASE_KEY_ALIAS = "manygram"
   keytool -genkeypair -storetype PKCS12 -keystore TMessagesProj/config/manygram.keystore -storepass $env:AGRAM_RELEASE_STORE_PASSWORD -keypass $env:AGRAM_RELEASE_KEY_PASSWORD -alias $env:AGRAM_RELEASE_KEY_ALIAS -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ά-Gram Personal Build, O=ά-Gram"
   ```

   Never commit the keystore or either password. Back up the release key securely: Android updates must be signed with the same key.

6. Build the arm64 standalone APK:

   ```powershell
   .\gradlew.bat :TMessagesProj_AppStandalone:assembleAfatStandalone --no-daemon
   ```

The resulting APK is under `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`.

## Implementation summary

- The 12.10.5 rebase preserves the local `.25-test.1` stability changes through a three-way merge, including container identity, persistent deleted media and Ghost Mode. New upstream media components require a fresh build and device regression run; an old APK is not proof that this update is compatible. Official-application-only passkey support is disabled for this fork.

- One automatic local container per Telegram engine account. Existing installations keep their slot and package identity so sessions migrate in place; the startup container picker has been removed.
- Encrypted container metadata with a random container ID and a distinct AES-256-GCM key generated in Android Keystore.
- Offline profile creation before Telegram authorization with an exact preview. Choose one of ten standard model/Android presets, generate another preset, or enter a custom model and Android version. The installed Agram version and private production `api_id` remain truthful, and `official_app` is not forged. The profile is locked after login; ending the Telegram session retires that container and frees the slot for a new profile.
- Optional per-container PIN and biometric UI gate on cold start, account switching, notification/deep-link entry and return after 60 seconds in background. The application keeps background delivery functional; this UI gate does not turn the existing Keystore metadata key into a user-authentication-bound key. False/legend codes are not included.
- Before authorization, the next free engine slot receives its container automatically. A login started after every account has been logged out follows the normal first-login lifecycle instead of the add-account branch.
- A profile saved after the network singleton has already been created is re-applied through JNI before authorization. Native datacenter init versions are reset so the next request carries the selected `device_model` and `system_version`, together with the real installed `app_version`, in a fresh `initConnection`.
- Per-container Ghost Mode has a master switch directly in the dialogs header, uses a highlighted active state, and applies only to the current container. A long press opens the detailed controls for read-receipt, story-view, typing/recording and online-presence suppression; replies/reactions can require confirmation because server-side interactions may still reveal activity.
- Automatic chat viewing and explicit read actions use separate paths. While read suppression is active, opening or scrolling through a regular chat does not mutate its local unread state, remove its notification, or send a receipt. The chat-list action and notification action “Mark as read” explicitly clear both local state and the server receipt; optional read-on-interaction does the same after a reply or reaction.
- When story-view suppression is enabled, an opened story keeps its unread ring and notification. The opening warning reflects the actual story setting instead of promising suppression from the Ghost master switch alone; reaction confirmation still depends on the warning setting.
- Per-container network source of truth and native proxy application: direct or a custom SOCKS5/MTProto proxy. This candidate contains no embedded Tor runtime, pluggable-transport dependency, bridge discovery, Tor control screen or Tor status UI.
- Telegram 12.10.5's new WEB proxy is explicitly unavailable in this candidate: its upstream process-global transport has not been adapted to account isolation. Selecting or importing it never starts that transport, changes a container route, or silently falls back to direct networking. SOCKS5 and MTProto remain supported.
- Container metadata schema 7 migrates the legacy `tor` network value to `direct`, clears the obsolete proxy fields and persists the result. This prevents upgraded containers from being stranded behind a route that no longer exists; users who still need a proxy can configure it explicitly for that container.
- Agram Push follows the selected container route and reconnects when it changes. HTTPS subscriptions support direct and SOCKS5 routes. An MTProto proxy can carry Telegram MTProto only, not an HTTPS push stream, so Agram Push remains disconnected in that mode instead of silently bypassing the selected route; ordinary MTProto push remains the explicit alternative.
- Built-in Agram Push transport with no distributor application. Each container owns a random endpoint registered with Telegram as Simple Push type 4; legacy duplicate instance identifiers are rotated and re-registered automatically. `other_uids` remains empty so accounts are not merged into one token. The foreground service shares only Android lifecycle management while every subscription is bound to an immutable container id, account slot and route. Ordinary MTProto push remains an explicit user choice and is never activated automatically as a route fallback.
- The default `ntfy.sh` relay sees connection metadata and an opaque Telegram wake value, but never message text, media, auth keys or the account identity stored by Agram. Endpoint URLs are capability secrets, remain encrypted locally and are not printed in the settings preview. Set `AGRAM_PUSH_BASE_URL` to a compatible self-hosted ntfy server when relay ownership or metadata separation is required.
- Per-container notification privacy: hide identity, show author only, or use full Telegram previews. The secure default is hidden.
- 32 stable Java/native engine slots and removal of the Premium account-count gate. Containers provide isolation and lifecycle management over those bounded engine instances; they do not turn the native engine into an unbounded process pool.
- Staggered lightweight connections for background accounts and lazy initialization of heavy Java controllers on selection.
- Re-entrancy protection during account switching and sparse-slot-safe notification media handling.
- Account selectors contain active sessions only. Confirmed logout retires its container; the slot is reusable only after durable cleanup. Temporary storage/Keystore failures or an inactive runtime identity do not prove logout and never justify replacing persisted session data.
- Cold start never infers logout from a temporarily inactive slot. Push-instance uniqueness uses a non-sensitive SHA-256 registry index instead of decrypting every account under one lock, preventing the Android Keystore startup ANR while confirmed logout still deletes its container.
- Separate package, account types, MIME types, shortcuts, broadcast actions, app name and launcher icon.
- Standalone foreground push service for use without Google Play Services. The default relay is configurable with `AGRAM_PUSH_BASE_URL` for reproducible self-hosting; only opaque wake signals pass through it, never Telegram message contents.
- Android direct-share conversation shortcuts are disabled to avoid exposing a dialog from another container. On Android 13+ Recent Apps screenshots are disabled without blocking ordinary in-app screenshots.
- arm64-only Pixel/GrapheneOS flavor and project-specific signing configuration.
- Telegram API credentials are read from private build settings. Public/test fallback credentials are rejected by the build.
- Minimum Android version is API 26. Sensitive Android backup is disabled, and legacy phone/SMS/call-log, overlay and background-location permissions were removed; self-update permission remains only in the standalone distribution manifest.
- CI checks guard production credential handling, minimum SDK, permissions, backup policy and the retention boundary.
- CI also executes production Push-state transitions on the JVM: independent stream/registration states, retry backoff, stale callbacks, slot reuse and concurrent account updates. An explicit manual CI workflow reconstructs the exact upstream source and builds an unsigned APK using private API configuration, without a signing key. Device tests remain mandatory.
- Push status separates HTTPS stream health from Telegram endpoint registration; runtime observations are not persisted as proof of a live socket after a restart. The relay hostname and safe error states are visible in notification settings. Invalid relay configuration cannot silently select another server.
- The single-scroll container settings group device profile, network, contacts, notifications, Ghost Mode, protection and storage into cards using Telegram radio/switch cells. Locked session profiles remain read-only. Settings writes run off the UI thread and report failures without advancing login.
- The login screen shows actionable messages for rejected test API credentials and GrapheneOS network failures instead of silently returning to the phone-number screen.
- Visible branding is ά‑Gram. The supplied blue-on-white SVG geometry is preserved as Android legacy, adaptive and monochrome launcher resources.
- Optional per-account retention of messages deleted on Telegram in regular private chats, groups and channels.
- Retained messages carry a persistent local-only marker, render at 40% opacity and show `{DELETED}` beside the time.
- When a message's primary media file has already been downloaded before Telegram deletes the message, Agram copies that file asynchronously into a deterministic `deleted_media` directory inside the account's container before ordinary cache cleanup can remove the source. The copy is written through a temporary `.part` file, synced and renamed only after completion; an archive is committed only while the same container still owns the account slot.
- File and image resolution prefer the archived copy for a retained deleted message. Pending source files are protected from cache cleanup until their copy either commits or fails, so clearing Telegram's cache does not remove the retained media archive.
- The archive is app-private, isolated with the rest of the container and deliberately outside Telegram's ordinary cache directories. It survives application updates and cache clearing, but confirmed logout/container removal, Android “Clear storage”, or uninstall removes it. Clearing ordinary cache is not an archive-delete action.
- Secret chats, TTL/self-destruct, view-once, protected and other ephemeral content never enter the persistent deleted-media archive.
