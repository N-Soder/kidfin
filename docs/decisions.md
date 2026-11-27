# Design notes

Why Kidfin works the way it does.

## Platform and toolchain

- **Single Gradle module `:app`, package `au.kidfin.tv`.** Engine packages (`session`, `media`,
  `security`, `power`) are pure Kotlin (coroutines only) and unit-tested on the JVM. Android code
  lives in `platform`, `jellyfin` and `ui`. In `ui`, `MainViewModel` is navigation and wiring;
  the grant (`ParentGate`), secret entry (`SecretPad`), shelf (`ShelfLoader`) and player
  (`PlaybackCoordinator`) are plain classes, and the view model takes an `AppServices`
  interface, so the grown-up gating is unit-tested with fakes rather than on a device.
- **compileSdk 37.2 / targetSdk 37 / minSdk 28.** API 28 is where `GLOBAL_ACTION_LOCK_SCREEN`
  exists. Compose 1.13 requires compileSdk 37.2.
- **Pinned versions only**, no dynamic or "latest" ranges; the Gradle wrapper checksum is pinned.
  Lint runs with warnings as errors, and warnings are fixed rather than suppressed.
- **Own name, colours and icon.** The display name is "Kidfin" (styled like "Jellyfin"); no
  Jellyfin logo or artwork is used.

## Session engine

- **Time is accounted in Jellyfin ticks** from `SystemClock.elapsedRealtimeNanos() / 100`. Only
  player "actually playing" edges open a charged interval; preparing, buffering, pause, Home and
  browsing are free. A backwards monotonic step charges nothing (and refunds nothing).
- **Deadline enforcement** is a 250 ms ticker calling an idempotent `checkDeadline()` while
  playing. Stale timers are harmless because the check reads current state.
- **One ending transition:** the first of deadline, completion, parent-end or no-eligible-title
  writes `Ending`; later callbacks are stale. Sign-off then `completeSignOff()` moves to `Locked`.
- **Persistence:** `AtomicFile` plus a small checksummed key=value codec in `noBackupFilesDir`,
  instead of DataStore. Two tiny files, synchronous atomic writes, explicit corruption
  detection, no extra dependency.
- **Fail-closed recovery:** a persisted open playing interval (crash, force-stop, reboot
  mid-play) restores as `Locked` (`RECOVERED_FAIL_CLOSED`). A persisted `Ending` restores as
  `Locked` with no standby retry. A corrupt file restores as `Locked`.
- **Leftover time does not wait forever.** A session with nothing playing for 30 minutes
  (paused, Pick another, Resume, or Kidfin in the background) ends and locks; coming back to it
  ends it quietly, without a goodbye. A session saved before a reboot restores as `Locked`. A
  saved session missing its boot or idle fields is treated as stale and locks.
- **A session whose first title won't start is withdrawn.** Approving starts the session and
  the title together; if the access check fails, Home is pressed, or the player fails before
  the title ever plays, the approval is cancelled rather than left with time and nothing to
  watch.
- **Critical writes:** session start, extension and opening a playing interval must persist or
  are refused, so playback never runs unaccounted. Other writes are best effort.
- **Extensions** are idempotent per request id, settle the open interval first, and are refused
  at exactly zero remaining (the ending wins). One extension per secret entry: after +5, +10 or
  +15 the menu closes, and more time needs the secret again.
- **"Choose something else" during a timed session** ends it without a goodbye or standby
  (`PARENT_SWITCHED_MODE`), because the grown-up is present and still setting up; the grant is
  kept so the next pick goes straight to approval. "End now" uses the full sign-off.
- **Last title may run 3 minutes over.** A title is eligible in a timed session when what is left
  to watch is at most the remaining allowance plus 3 minutes, and the 15/30/60 presets use the
  same rule (a 33-minute title fits 30 minutes). The 3 minutes are kept as a reserve: children
  see the allowance without it, the playing title is cut off only when the reserve is also spent,
  and once the allowance itself is used the session ends when that title stops. Pausing in the
  reserve does not end it. Partly watched titles (1-97% played) count only what is left, and
  timed sessions resume them from the server's stop point.
- **Labels:** runtimes round up, remaining time rounds down; labels are never used for
  eligibility.

## Grown-up approval

- **Secret arrow moves instead of a visible keypad.** On a D-pad remote a keypad's focus
  highlight shows children which digits are entered. The secret is six arrow presses (4,096
  combinations); the screen shows only filling dots, an invisible focus target takes the presses
  and OK starts again. Stored as PBKDF2-HMAC-SHA256, 120k iterations, 16-byte salt, constant-time
  compare. Four free tries, then a 30 s cooldown doubling to a 3 min cap, shown as a live
  countdown. Failures are forgotten an hour after the last one. The cooldown is anchored to the
  boot and restarts in full after a reboot. A failed attempt that cannot be recorded is
  refused. Corrupt secret data locks the app; reset by clearing app storage.
  Records without a format field are read as legacy 4-digit PINs, accepted once and then
  replaced.
- **Grant:** a successful entry lasts 2 minutes and is re-checked inside every parent action,
  not just when a parent screen is shown. Key presses on settings screens (the locked menu,
  Jellyfin sign-in, libraries, TV power) restart the 2 minutes, never beyond 15 minutes from the
  entry, so a sign-in typed on the TV keyboard doesn't lapse halfway. Presses on Approve or the
  in-session menu never renew it: every press there starts or adds time, so an abandoned grant
  lapses 2 minutes after the secret. Starting playback, an extension, leaving parent screens and
  going to the background all drop the grant. If it lapses mid-task the secret is asked again
  and leads back to the same title or setup step. The grant from choosing the secret at first
  run carries straight into Jellyfin setup.
- **Leaving Kidfin needs the secret.** Back on the shelf, Pick another or Resume asks for the
  secret before Kidfin goes to the background; Back on that screen carries on. Leaving never
  keeps a grant. Home can't be blocked by an app (see the threat model).
- **Content first.** The locked screen is a shelf of every playable title in the permitted
  libraries (valid runtime only). The child picks; nothing plays until a grown-up approves:
  - If the title fits a preset (15, 30 or 60 minutes), "How long?" disables presets shorter
    than the title and focuses the shortest fitting one, plus a quiet "Just this one".
  - If it is longer than 60 minutes: "It's a long one" (or, when its length is unknown, "We
    don't know how long this one is"), then "Play just this one" or "Pick something else".

  The rules live in `session/Approval.kt` and are unit-tested.
- **Threat model.** The app lock stops reopening Kidfin only. It does not stop Home, other apps,
  other inputs or uninstalling; use Google TV kids profiles for that. It is a convenience barrier
  for a cooperative household, not tamper-proof parental control.

## TV power

- **Native standby.** An app-owned accessibility service performs `GLOBAL_ACTION_LOCK_SCREEN`,
  which on a Chromecast with Google TV puts the device to sleep and sends CEC `<Standby>` to the
  TV. Tested on one Chromecast with Google TV 4K (Android 14) with one TV: five of five attended
  cycles, panel in standby about a second after the request. Other devices and TVs may differ,
  which is why the parent menu has a Test button.
- **Least-privilege service:** no events, no window content, no key filtering, no gestures,
  `isAccessibilityTool=false`. The app does not change Settings itself; the parent TV power
  screen explains the steps and opens Settings. If the service is not connected, requests report
  `Unavailable`, Kidfin just locks, and the TV power setting shows "Off".
- **On-screen check is inside the policy:** standby is requested only if the app is still on
  screen at the moment of the request. Home during the goodbye skips it. No TV-input check is
  needed: native standby only ever runs while Kidfin is on screen.
- **`Locked` is persisted before the standby request**, so the device sleeping mid-call, a
  failed request or process death all leave the app locked.
- **Service and controller live in `platform/NativeStandby.kt`**, so `power/` stays pure Kotlin.

## Jellyfin

- **SDK 1.9.4 (LGPL-3.0, unmodified) requires server 12.0.0 or newer** (recommended 12.4.0).
  Jellyfin dropped the "10." prefix at 12.0. Items come from `libraryApi.getItems`, play-state
  reports from `sessionApi.reportPlayback*`; there is no HLS segment API, so stopping playback
  ends the transcode for that PlaySessionId.
- **Only the parent-entered URL is used**, never addresses the server advertises (a server in a
  container reports an internal address).
- **Parent setup on the TV** (Grown-ups, then Jellyfin): server address, username, password. The
  password lives only in the composition until the sign-in call. **Admin accounts are refused.**
  If the account can see every library, the library screen suggests restricting it in Jellyfin.
  The parent picks the libraries; nothing reaches the child until both are done. A 401 signs the
  app out and the shelf says a grown-up must sign in again. While not connected, a timed session
  never concludes "nothing fits".
- **Cleartext HTTP is allowed** (`usesCleartextTraffic` in the manifest) because the address is
  user-entered and most home servers on a LAN serve plain HTTP. Certificate validation is never
  relaxed: system CAs only, no user CAs, no trust-all. The sign-in form warns when the address is
  not `https://`. Accepted risk on plain HTTP: the password (once) and the token (every request)
  cross the LAN unencrypted. Mitigated by using a dedicated non-admin account limited to the
  kids' libraries. A DHCP reservation keeps the address stable.
- **Secrets:** the access token is AES-GCM encrypted with a non-exportable Android Keystore key
  in `noBackupFilesDir`; the config file holds no token. Media and artwork send the token in the
  Authorization header, and only to the configured server's origin. Transcode URLs carry `ApiKey`
  in the query, so stream URLs and player error messages are never logged (error code names
  only). The SDK logs through SLF4J bound to NOP, so it cannot put URLs into logcat. Image URLs
  carry no token; the artwork cache is bounded (64 MB disk, 15% RAM).
- **Library names are configuration**, chosen on the TV setup screen, never hard-coded.
- **Playback:** the device profile is built from the real decoders (`MediaCodecList`) plus HDMI
  passthrough formats (Media3 `AudioCapabilities`). Direct play when possible; otherwise server
  HLS with HEVC/H.264 video copy, so titles with an audio track the device cannot decode (for
  example TrueHD) get an audio-only transcode. Plain Dolby Vision only with a DV decoder. No
  subtitles. Buffering, audio-focus loss and pause are uncharged; a player error stops playback
  with the allowance (and One title position) kept. No seek, no speed, no MediaSession, no
  background audio.
- **Jellyfin 12 quirk:** `GetItems` applies `recursive` when filters plus `includeItemTypes`
  are used; the catalogue query was checked against what `EligibleCatalogue` expects.

## Shelf

- **Sections:** Keep watching (Jellyfin resume items, then next-up episodes, one per series),
  Favourites (titles hearted for the account lead, up to two lines, then most played; series
  summed over episodes; unplayed titles never appear) and Everything (a daily shuffle: stable all
  day, new order tomorrow, independent of server order). Rows are 7 posters wide. This replaces
  an earlier "server order, never randomised or engagement-ranked" rule.
- **One poster per series.** Picking it plays Jellyfin's next-up episode, else the first; that
  episode is what gets approved and access-checked. Shows open an episode list with season tabs,
  stills, watched ticks and the next episode marked and focused, so one press still plays it.
  Timed sessions do not autoplay the next episode; they return to "Pick another".
- **Endless scroll:** the whole catalogue for the chosen libraries is fetched in one parallel
  round (titles, resume, next up, played episodes per library; unsorted, no total count) and laid
  out lazily; posters load as they scroll into view.
- **Duplicates:** same title, year and kind collapse to the longest copy; different years stay.
- **Disk copy of the shelf** (`cacheDir/shelf.cache`, checksummed, tied to server, account and
  libraries; titles and image URLs only, no token) is shown at once on launch while a fresh copy
  loads. It refreshes after a session ends, after "Choose something else", and when older than 5
  minutes. A failed refresh keeps the shown copy; play-time access checks are unchanged.
- **Hearts** are the account's Jellyfin user data, not server settings. A grown-up hearts or
  un-hearts on the approve screen after approval; an episode hearts its whole series.
- **Search:** a magnifier next to the cog. Large on-screen A-Z/0-9 keys (no system keyboard),
  results update per key from the shelf already in memory. Matching ignores case, accents,
  punctuation and "the/a/and/of"; every typed word must match a title word by prefix,
  containment (3+ letters) or within 1-2 typos. Titles only. Search is child-facing; picking
  still needs approval.
- **Release builds** are not minified. A release build cut launch-to-posters from about 14 s
  (debug) to about 1.5 s on a Chromecast with Google TV.
- **Timed "Pick another"** still pages episodes via `EligibleCatalogue`, because it must offer
  only what fits the time left.

## Look

Dark "Storybook" night palette (a TV in the evening): Fraunces headings, Outfit body (both OFL,
bundled in `res/font`, licences in `assets/licences`), tomato/teal/marigold/plum/sky tones, cards
with a tone border and lip. Tokens in `ui/Theme.kt`. The logo is the wordmark with a purple
jellyfish as the dot on the i; the launcher icon is the jellyfish and the goodbye shows it
asleep. Original artwork, not Jellyfin's mark.
