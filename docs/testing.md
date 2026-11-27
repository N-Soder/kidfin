# Test plan

Automated checks run in CI and locally:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Lint warnings are errors. The unit tests cover the session engine (accounting, endings,
extensions, stale callbacks, storage failure, recovery), the approval rules, the secret-move guard
(hashing, cooldown, reboot, corruption, grant expiry), the grown-up gating in the view model
(every parent-only action refuses a lapsed grant, renewal and its 15-minute cap, one extension
per entry, leaving Kidfin, first-run setup), catalogue eligibility, shelf, shelf cache and search,
standby policy, and Jellyfin helpers (address normalising, config codec, same-origin auth,
device profile).

The manual checks below need a TV or emulator (Google TV) and a Jellyfin server. Note the date
and device with each result when you run them.

## Setup and sign-in

- Not set up: the shelf says Kidfin is not connected, with no titles.
- First run asks for the grown-up secret twice; a mismatch starts again, a match goes straight
  on to Jellyfin sign-in without asking for the secret again.
- Grown-ups, then Jellyfin: the TV keyboard's Next/Done moves through address, username, password.
- An address not starting with `https://` shows "Not encrypted: use only on your home network."
- Wrong password: "Wrong username or password.", password cleared, nothing saved.
- An admin account is refused. A server older than the minimum version is refused.
- The library picker lists libraries; nothing shows to the child until one is chosen.
- Logcat contains no server URL, token, password or authorization header.

## Shelf and approval

- The shelf shows Keep watching, Favourites and Everything with artwork; titles with invalid
  runtimes are hidden; search works with the on-screen keys.
- Pick a title: the approval screen asks for the secret and names the title. Presets shorter
  than the title are disabled; the shortest fitting one is focused.
- A title longer than 60 minutes offers "Play just this one" or "Pick something else".
- A show opens its episode list with the next episode focused.
- Hearting or un-hearting on the approval screen shows in Jellyfin.
- Library refused by the server at play time: pick a film, enter the secret and stop on
  "How long?". In Jellyfin, remove that library from the child account's access. Press a preset:
  Kidfin refuses ("That video isn't available right now"), nothing plays and no session starts.
  Restore the access afterwards. Passed 2026-11-27 on Google TV. The server may end the child
  account's sign-in when its access changes; Kidfin then shows nothing until a grown-up signs in.
- Unticking a library in Kidfin's picker removes its titles from every shelf row and from search.
- Back from the secret or the approval screen returns focus to the poster that was picked.
- Back on the shelf asks for the secret to leave Kidfin; Back again stays. Home always leaves.

## Timed sessions

- Pick 30 minutes: the title plays at once. Pause and buffering do not use time.
- Home mid-play, wait, relaunch: allowance unchanged.
- Force-stop mid-play, relaunch: the app is locked.
- Allowance used: goodbye once, then locked. A title may run up to 3 minutes over; pausing in
  the reserve does not end it.
- Grown-ups, +10 minutes: remaining time grows and the menu closes; adding more needs the
  secret again.
- "Choose something else": no goodbye, back to the shelf.
- "End now": goodbye, then locked.
- Back during a timed session shows "Pick another" with only titles that fit. Back there asks
  for the secret to leave Kidfin.
- Leave a session paused for 30 minutes: it ends and locks.

## One title

- Approve "Just this one": it plays; Back saves the position and shows "Carry on watching".
- Natural end: goodbye once, then locked.

## Playback

- Direct play of a normal file; HLS transcode for a title with an unsupported audio codec;
  stop reports appear in the Jellyfin dashboard.
- Revoking the device's session in Jellyfin: the app signs out and the shelf asks a grown-up to
  sign in again.
- Network loss during playback shows an error without ending the session.

## TV power (native standby)

- Without the "Kidfin TV power" accessibility service enabled, TV power shows "Off" and the TV
  stays on after a session ends.
- Enable the service in Settings, Accessibility; TV power flips to "On" and the Test button
  appears, with Cancel focused on its confirm screen.
- Test, then "Turn TV off": the TV enters standby. Wake it: Kidfin is in front and locked.
- End a session with the app on screen: goodbye, then standby. Press Home during the goodbye:
  no standby.
- Record the device, Android version and TV model with the result; CEC behaviour varies.

## Known gaps

- Reboot mid-session and wall-clock changes are unit-tested only.
- Secret-move lockout via the UI and the "no eligible title" ending are unit-tested only.
