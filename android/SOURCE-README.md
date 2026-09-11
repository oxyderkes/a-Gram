# ά‑Gram source overlay

This archive contains the files changed for the stable ά‑Gram `12.10.1-a-gram.24` Android build. It intentionally does not contain Telegram API credentials, a private signing keystore, generated build output, Android SDK, NDK or Gradle caches.

## Base source

- Repository: https://github.com/DrKLO/Telegram
- Commit: `62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c`
- Upstream version: `12.10.1 (7038)`
- License: GNU GPL v2 or later; see `LICENSE`.

The overlay preserves paths relative to the repository root. Copy it over a checkout of the exact commit.

The Android application ID and signing identity intentionally retain their pre-rebrand Manygram values so ά‑Gram can be installed as an in-place update without deleting local app data.

## Reconstruct and build

1. Clone the upstream repository with submodules and check out the commit above.
2. Copy every file from this overlay over the repository root.
3. Copy `local.properties.example` to `local.properties`, set `sdk.dir`, and add private production `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` values obtained from https://my.telegram.org/apps. The build now fails when these values are absent or malformed; there is no public test fallback.
4. Install JDK 17, Android SDK 36, Build Tools 36.0.0, NDK `27.2.12479018` and CMake 3.22.1.
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

- One automatic local container per Telegram engine account. Existing installations keep their slot and package identity so sessions migrate in place; the startup container picker has been removed.
- Encrypted container metadata with a random container ID and a distinct AES-256-GCM key generated in Android Keystore.
- Offline profile creation before Telegram authorization with an exact preview. Choose one of ten standard model/Android presets, generate another preset, or enter a custom model and Android version. The installed Agram version and private production `api_id` remain truthful, and `official_app` is not forged. The profile is locked after login; ending the Telegram session retires that container and frees the slot for a new profile.
- Optional per-container PIN and biometric gate on account switching. False/legend codes are not included; the v2 metadata migration deletes hashes created by the earlier experimental implementation.
- Before authorization, the next free engine slot receives its container automatically. A login started after every account has been logged out follows the normal first-login lifecycle instead of the add-account branch.
- A profile saved after the network singleton has already been created is re-applied through JNI before authorization. Native datacenter init versions are reset so the next request carries the selected `device_model` and `system_version`, together with the real installed `app_version`, in a fresh `initConnection`.
- Per-container Ghost Mode has a master switch directly in the dialogs header, uses a highlighted active state, and applies only to the current container. A long press opens the detailed controls for read-receipt, story-view, typing/recording and online-presence suppression; replies/reactions can require confirmation because server-side interactions may still reveal activity.
- Automatic chat viewing and explicit read actions use separate paths. While read suppression is active, opening or scrolling through a regular chat does not mutate its local unread state, remove its notification, or send a receipt. The chat-list action and notification action “Mark as read” explicitly clear both local state and the server receipt; optional read-on-interaction does the same after a reply or reaction.
- Story viewing follows the same local-preservation rule: in Ghost Mode an opened story keeps its unread ring and notification. Opening a story and sending any story reaction use explicit confirmation dialogs.
- Per-container network source of truth and native proxy application: direct or a custom SOCKS5/MTProto proxy. This stable build contains no embedded Tor runtime, pluggable-transport dependency, bridge discovery, Tor control screen or Tor status UI.
- Container metadata schema 7 migrates the legacy `tor` network value to `direct`, clears the obsolete proxy fields and persists the result. This prevents upgraded containers from being stranded behind a route that no longer exists; users who still need a proxy can configure it explicitly for that container.
- Agram Push follows the selected container route and reconnects when it changes. HTTPS subscriptions support direct and SOCKS5 routes. An MTProto proxy can carry Telegram MTProto only, not an HTTPS push stream, so Agram Push remains disconnected in that mode instead of silently bypassing the selected route; ordinary MTProto push remains the explicit alternative.
- Built-in Agram Push transport with no distributor application. Each container owns a random endpoint registered with Telegram as Simple Push type 4; legacy duplicate instance identifiers are rotated and re-registered automatically. `other_uids` remains empty so accounts are not merged into one token. The foreground service shares only Android lifecycle management while every subscription is bound to an immutable container id, account slot and route. Ordinary MTProto push remains an explicit user choice and is never activated automatically as a route fallback.
- The default `ntfy.sh` relay sees connection metadata and an opaque Telegram wake value, but never message text, media, auth keys or the account identity stored by Agram. Endpoint URLs are capability secrets, remain encrypted locally and are not printed in the settings preview. Set `AGRAM_PUSH_BASE_URL` to a compatible self-hosted ntfy server when relay ownership or metadata separation is required.
- Per-container notification privacy: hide identity, show author only, or use full Telegram previews. The secure default is hidden.
- 32 stable Java/native engine slots and removal of the Premium account-count gate. Containers provide isolation and lifecycle management over those bounded engine instances; they do not turn the native engine into an unbounded process pool.
- Staggered lightweight connections for background accounts and lazy initialization of heavy Java controllers on selection.
- Re-entrancy protection during account switching and sparse-slot-safe notification media handling.
- Account selectors contain active sessions only. Ended, blocked and revoked sessions are removed during logout or migration, and their account slot becomes immediately reusable instead of leaving a non-actionable local card.
- Cold start never infers logout from a temporarily inactive slot. Push-instance uniqueness uses a non-sensitive SHA-256 registry index instead of decrypting every account under one lock, preventing the Android Keystore startup ANR while confirmed logout still deletes its container.
- Separate package, account types, MIME types, shortcuts, broadcast actions, app name and launcher icon.
- Standalone foreground push service for use without Google Play Services. The default relay is configurable with `AGRAM_PUSH_BASE_URL` for reproducible self-hosting; only opaque wake signals pass through it, never Telegram message contents.
- Android direct-share conversation shortcuts are disabled to avoid exposing a dialog from another container. On Android 13+ Recent Apps screenshots are disabled without blocking ordinary in-app screenshots.
- arm64-only Pixel/GrapheneOS flavor and project-specific signing configuration.
- Telegram API credentials are read from private build settings. Public/test fallback credentials are rejected by the build.
- Minimum Android version is API 26. Sensitive Android backup is disabled, and legacy phone/SMS/call-log, overlay and background-location permissions were removed; self-update permission remains only in the standalone distribution manifest.
- CI checks guard production credential handling, minimum SDK, permissions, backup policy and the retention boundary.
- The login screen shows actionable messages for rejected test API credentials and GrapheneOS network failures instead of silently returning to the phone-number screen.
- Visible branding is ά‑Gram. The supplied blue-on-white SVG geometry is preserved as Android legacy, adaptive and monochrome launcher resources.
- Optional per-account retention of messages deleted on Telegram in regular private chats, groups and channels.
- Retained messages carry a persistent local-only marker, render at 40% opacity and show `{DELETED}` beside the time.
- When a message's primary media file has already been downloaded before Telegram deletes the message, Agram copies that file asynchronously into a deterministic `deleted_media` directory inside the account's container before ordinary cache cleanup can remove the source. The copy is written through a temporary `.part` file, synced and renamed only after completion; an archive is committed only while the same container still owns the account slot.
- File and image resolution prefer the archived copy for a retained deleted message. Pending source files are protected from cache cleanup until their copy either commits or fails, so clearing Telegram's cache does not remove the retained media archive.
- The archive is app-private, isolated with the rest of the container and deliberately outside Telegram's ordinary cache directories. It survives application updates and cache clearing, but confirmed logout/container removal, Android “Clear storage”, or uninstall removes it. Clearing ordinary cache is not an archive-delete action.
- Secret chats, TTL/self-destruct, view-once, protected and other ephemeral content never enter the persistent deleted-media archive.
