# Isolation and stability hardening — local test candidate

This document describes implementation boundaries, not proof of complete OS isolation or a device-tested stable release. No Tor or chat-export feature is reintroduced.

## Session and contact ownership

Each session has a generation in addition to its container UUID. Deferred saves, login completion, logout responses, push work and settings changes must still belong to that owner. An inactive runtime account with a persisted identity cannot be offered as a free login slot. Only a confirmed, durably persisted logout may remove that identity. Preference commit failures roll back in-memory preference values; failed cleanup leaves the slot unavailable for reuse instead of guessing that deletion succeeded.

The native `tgnet.dat` payload now stores its owner UUID alongside the DC key material in a tagged trailing extension. An already matching active owner does not need a fresh write merely to open its session. The first migration of an active legacy session preserves its existing keys; a mismatched or unreadable active owner is quarantined instead of overwritten. Native retirement clears pre-login requests as well as authenticated requests, DC keys and connections, and requires a durable acknowledgement before the Java container can be removed. An explicit logout intent is distinct from a temporarily unloaded account and is recoverable after restart. A replacement owner remains network-blocked until its own route is applied.

Existing encrypted metadata is opened with its existing Keystore key. Missing/unavailable keys do not trigger generation of a replacement key over existing ciphertext. These guards are not a recovery tool for keys already lost by Android or a previous build.

Phonebook synchronization is an explicit per-account choice, off for new logins. Legacy default-on values without the new explicit choice require confirmation again. Android permission alone is not consent. Turning synchronization off does not delete contacts already uploaded to Telegram. Reads, writes, uploads and queued work check the current account's consent/session generation.

## Push and network audit

The legacy global FCM path is disabled at provider, callback, registration, native init and standalone-manifest/dependency boundaries. Old global token/auth-key preferences and dedicated Instance ID token entries are removed narrowly with failure/retry handling; account preferences are not cleared. Agram Push retains one subscription/endpoint per container. A shared foreground service is lifecycle plumbing, not a common Telegram registration token. Stream connectivity and Telegram registration remain separate states.

This local cleanup does **not** confirm revocation of old server-side FCM registrations or erase Telegram's historical associations. The migration does not retransmit a former shared token across every account; offline accounts could not reliably complete such revocation anyway. Do not describe it as retroactively unlinking accounts.

| Traffic | Current boundary |
| --- | --- |
| MTProto messages and Telegram media | Account-specific native engine and configured direct/SOCKS5/MTProto route |
| Agram Push HTTPS | Container-bound direct or SOCKS5 subscription; MTProto proxy cannot carry HTTPS, so there is no direct fallback |
| ImageLoader external HTTP and DC discovery | Immutable owner checks; direct-only operations are blocked for proxy containers; revoked HTTP handles cannot be reused |
| VoIP and native live streaming | Direct-only for now; proxy mode blocks these engines and a route change stops existing engines |
| Proxy-hostname bootstrap | Resolving a named proxy necessarily precedes reaching it; system DNS is not claimed to travel inside the proxy |
| WebViews, mini apps, payments, external browser, third-party map/translation SDKs | Not covered by a claim of a per-container VPN or isolated browser profile; separate review/transport adaptation remains required |

Native teardown and a socket operation already in progress can race route changes. This application-level guard is not a zero-packet firewall. Android UID/process, OS networking, some public/global assets and WebView infrastructure remain shared. Account separation does not guarantee that Telegram or another service cannot correlate accounts.

### Remaining direct-network surfaces found by source audit

The direct-HTTP/WebView scan also found the following upstream paths. They are **not fixed or routed by the ImageLoader guard** and must not be included in a claim that all app traffic follows the account proxy:

- `ui/bots/BotDownloads.java`, `ui/web/BotWebViewContainer.java`: bot downloads and web-app requests.
- `ui/web/HttpGetTask.java`, `HttpPostTask.java`, `HttpGetFileTask.java`, `HttpGetBitmapTask.java`: embedded-browser helpers.
- `ui/PaymentFormActivity.java`: payment-provider HTTP and WebViews.
- `ui/Components/WebPlayerView.java`, `PhotoViewerWebView.java`, `EmbedBottomSheet.java`: external embedded players/media.
- `ui/Components/TranslateAlert2.java`: third-party translation requests.
- `ui/WebviewActivity.java`, `ui/web/WebInstantView.java`: WebView/browser content.

These need explicit account ownership and an appropriate transport, or a clear refusal before use in a proxy-only container. Merely creating a separate native Telegram session does not isolate browser cookies, SDK traffic or external activities. No claim is made that this source scan enumerates native/SDK traffic exhaustively.

## Ghost Mode

Ordinary automatic content-read/voice/video and mention paths honor read suppression, as do automatic reaction/poll unread updates. Explicit mark-read remains explicit. The story warning describes the actual story-view setting. Sending, reacting, calls and other server interactions can reveal presence. Aggregate channel-view counting is not promised hidden. Secret Chats, TTL, self-destruct and view-once handling remain upstream behavior, not extra retention.

## Archive and deletion

An archive copy validates its expected byte length and immutable owner, writes a temporary file, flushes/syncs it, then commits it. Truncated/growing inputs, cancellation and failed output writes do not produce a completed archive entry. Retry and cache-protection mechanisms remain active.

- **Clear cache:** does not intentionally remove committed persistent archive files; pending source files remain protected.
- **Clear local database:** a different destructive operation; can remove retained message rows and associated media. The confirmation now says so explicitly.
- **Delete messages/history, confirmed logout/container removal, Android clear-storage or uninstall:** may remove retained data; these are not ordinary cache cleaning.

SQLite/message media do not gain additional encryption in this revision. Existing metadata encryption and the PIN interface lock must not be described as encryption of the whole archive. A separate database/file-encryption design needs key lifecycle, migration, streaming and recovery analysis.

## Verification boundary

The automated suite exercises production policy/transaction/copy/HTTP helpers and source invariants: stale generations, failed commits and rollback, failed logout, independent push state, cache cleanup retry, interrupted/truncated copies and Ghost exclusions. JVM fault injection does not emulate Android Keystore hardware, a real disk-full filesystem, process death or multi-account database migration.

The native regression harness compiles the actual production `Config.cpp` and ownership policy against disposable files with injected I/O failures. On Windows, POSIX permissions and directory `fsync` use test shims; passing that harness is not evidence about Android's filesystem durability. The Android native build and the device upgrade/logout/reuse checks remain separate verification layers.

Use dedicated accounts for logout/reuse, reboot, inaccessible Keystore, disk-full and large-chat/account-switch stress checks listed in STABILITY-CHECKLIST. Never clear a user's real application data to obtain a passing test. No performance improvement percentage or complete runtime stability is claimed from a successful build.
