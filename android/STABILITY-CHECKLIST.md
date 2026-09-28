# Android stability gate — 12.10.5-a-gram.26-test.6

This is a **pre-release test candidate**, not a device-verified stable release. No Tor runtime or chat export is included. A limited in-place .26-test.5 → .26-test.6 update and cold-start smoke passed on one Pixel/GrapheneOS account; the full device gate below remains outstanding.

## Automated checks

- Run `python .github/scripts/verify_android_overlay.py` from the overlay repository.
- Run `python .github/scripts/run_android_regressions.py` with JDK 17.
- After compilation, run `python .github/scripts/verify_android_manifest.py --manifest <merged-standalone-AndroidManifest.xml>`; source `tools:node="remove"` directives alone do not prove final package cleanup.
- Run `python .github/scripts/verify_android_themes.py --apk <path-to-apk>` after packaging. Bundled theme bytes must match the pinned upstream assets; test CRLF parsing and binary wallpaper boundaries.
- Upgrade over the failed `.26-test.1` without clearing data. Verify readable Intro title, body, language link, theme switch and start button in both dark and light modes, then cold-start to test persisted selection and replacement of cached CRLF assets. Also inspect the pre-login setup screen in both themes.
- Reconstruct the exact upstream commit documented in SOURCE-README and compile the arm64 standalone variant. Use `-PAGRAM_UNSIGNED_BUILD=true -PRELEASE_STORE_PASSWORD=unsigned-unused -PRELEASE_KEY_PASSWORD=unsigned-unused -PRELEASE_KEY_ALIAS=unsigned-unused` to verify without access to a signing key. The placeholders only allow unrelated upstream modules to configure; Standalone explicitly disables signing in this lane.
- A new artifact must have a unique increasing versionCode. Never distribute different APK contents under one test version. Keep the package and signing certificate unchanged for in-place updates.
- Verify the restored single-scroll account settings before/after login: all cards and the bottom save/continue button are reachable, locked profiles remain disabled, back exits without saving unchanged settings, and unsaved edits require confirmation. Record account identity before/after the in-place update without logging auth credentials.
- Optional manual CI workflow `Android unsigned verification build` uses private repository API secrets and no release signing key. Passing source checks does not replace this build or the device checks below.

## Required device checks before calling it stable

Use dedicated test accounts and a Pixel/GrapheneOS device; do not clear app data or uninstall to make a failing upgrade pass.

1. Upgrade over the currently installed no-Tor baseline (including .26-test.5, or an older no-Tor build if already installed) with the original signing certificate. Do not install the rejected export build just for this test. Verify all account identities, selected account, chats, proxy and Ghost settings. Repeat cold start and phone reboot. For upgrades from 12.10.1 to 12.10.5, explicitly validate database migration and replay of pending operations without resetting accounts.
2. With two or more accounts, log out of one; confirm that only its container is retired. Log into the free slot; no previous profile, cache, PIN or pending callbacks may reappear.
3. Enable container PIN, restart the app, open from launcher and notification/deep link, return after more than a minute in background. Content must remain covered until the correct PIN/biometric succeeds. Cancel a switch; the previous account must not become newly unlocked.
4. Simulate disk-full/write failure and temporarily unavailable Keystore in a controlled test installation. The old container mapping must remain recoverable; the UI must not report a successful save or delete a session.
5. Download the same attachment in two test accounts. Verify separate container paths; clear one account's media cache and confirm the other's files remain. Validate migrated legacy files by account message references; ambiguous files must never be guessed into a new container.
6. Keep an ordinary deleted message with downloaded photo/video/document. Clear cache, kill and restart the process mid-copy, and reopen the media. Confirm archive status/retry works and saved content is never automatically evicted when storage is low.
7. In Ghost Mode open unread chats and stories from the feed and profile/list. Confirm local unread counters remain; inspect test-account observations for receipts/views. Explicit mark-read, replies/reactions and their warnings must be tested separately.
8. In hidden-notification mode inspect notifications, Android channel names/groups, notification history, avatars/actions, and account switching. No contact/chat identity may remain in newly generated channels or notifications.
9. Test direct, SOCKS5 and MTProto-proxy routes, Wi-Fi/mobile changes and Doze. Break one proxy and verify other containers remain connected. Push stream and Telegram registration must report independent status; neither MTProto nor Agram Push may silently fall back to direct networking for a selected proxy. Separately audit the remaining browser/payment/SDK exceptions in HARDENING-26-test.5.md; do not claim these are already proxy-isolated.
10. Open large chats, scroll media, inspect archive status during downloads, and switch accounts repeatedly. Record ANR/crash logs, cold-start duration, frame jank and memory before/after. Never infer a performance percentage from compilation alone.
11. Exercise the new upstream Media3/native stack: archived and ordinary video, voice/audio, streaming, seek/pause/resume, and encrypted-cache media in two accounts. Test calls, animated stickers, emoji and Android 13+ vibration. These paths changed upstream and must not silently lose Agram's account-scoped file/key resolution.
12. Attempt WEB-proxy selection and import while using both direct and SOCKS5 routes. The unsupported notice must appear without changing the active container route or starting a global WEB transport. Confirm SOCKS5/MTProto configuration still works for the selected account only.
13. On a Google-enabled test installation, upgrade with old FCM preferences/token cache present. Verify the merged manifest contains no Firebase Messaging receiver/service/init provider, legacy callbacks are inert, and only each container's own Simple Push registration appears. Simulate failure of token-cache cleanup and retry without clearing unrelated account preferences.
14. Keep contacts permission granted but synchronization off in account A; enable it explicitly only in B. Verify no phonebook import/write occurs for A, including pending callbacks. Leave A's settings open, log out in a dedicated test installation, reuse its slot and verify the stale settings screen cannot grant the new account consent.
15. Queue ImageLoader HTTP media/DNS work, switch routes, log out/reuse the slot, then release pending responses. No old callback may publish content or apply configuration to the new session. A selected proxy must block unsupported VoIP/live-stream/ImageLoader direct HTTP paths with visible limitations rather than silently using direct transport.
16. Exercise voice/video content reads, mentions, reactions and poll notifications in Ghost Mode, both with and without read-on-interaction. Check explicit mark-read separately. Secret Chats and ephemeral-media lifecycle must remain upstream behavior. Verify the story warning when story suppression is off but another Ghost feature is on.
17. Interrupt archived-media copying, inject a truncated/growing source and a failed output write, then retry after cache cleanup and app update. Check that the temporary output is never presented as complete and pending source protection remains. Inspect separate cache/database/logout deletion warnings without performing destructive operations on real accounts.
18. On dedicated accounts only, interrupt explicit logout before/after identity clearing, native retirement acknowledgement and container deletion. Restart must resume that exact UUID's retirement, never erase another slot or treat a temporarily unreadable persisted user as inactive. An old response or pre-login request must not reach a replacement account.
19. Migrate an active legacy native config once, restart an already-bound config with no writable space, and inject a native handshake-key write failure. Existing durable keys must remain preserved; new authorization must not proceed with keys that could not be committed. Inspect the local-storage diagnostic rather than expecting a server logout.

## Diagnostics to record

Exact versionName/versionCode, device/OS, action immediately before failure, sanitized crash stack, connectivity and archive states. Do not include auth keys, PINs, proxy passwords, endpoint capability URLs, or message text in reports by default.

## Migration boundaries

- UUID media directories and in-memory image keys separate private account media. This is still one Android application/process, not an OS sandbox per account; explicitly public gallery exports and global wallpaper/emoji assets remain shared.
- Legacy shared files are copied only when an account's message/path records establish ownership. Ambiguous files and legacy encrypted-cache keys are not guessed or imported; some old thumbnails/media may need to download again. Existing shared files are not broadly deleted during migration.
- The archive is app-private persistent storage, not a claim that all message/media bytes are additionally encrypted by the new PIN. The PIN is an interface lock; Android app/device storage protection still matters.
