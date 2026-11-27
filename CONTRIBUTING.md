# Contributing

Thanks for helping. Kidfin is a small, opinionated app, so please open an issue before a large
change.

- Build and test: `./gradlew testDebugUnitTest lintDebug assembleDebug` must pass. Lint warnings
  are errors; fix them rather than suppressing them.
- Keep engine code (`session`, `media`, `security`, `power`) pure Kotlin and unit-tested.
- Pin dependency versions in `gradle/libs.versions.toml`; no dynamic versions.
- Never commit credentials, keystores, server addresses, tokens or logs. Use `192.0.2.x` and
  `jellyfin.example` in tests and docs.
- Do not log URLs, tokens or passwords.
- Changes touching the grown-up approval, session accounting or secrets need tests.
- Follow `docs/testing.md` for anything that needs a real TV, and say which device you used.
- By contributing you agree your work is licensed under GPL-3.0-or-later.
