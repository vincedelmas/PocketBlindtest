# Pocket Blindtest

A tiny native Android music guessing game for a Nothing Phone (3a) running Android 16 (API 36). Java, platform widgets and media APIs, no runtime dependencies, ads, accounts or analytics.

## Play

1. Allow audio access. The app reads songs indexed in Android's local music library (at least five seconds long).
2. Play the intro: **0.15 s → 0.5 s → 2 s → 5 s**.
3. Search your library by title or artist, select a song and confirm your guess. A wrong answer or skip uses one attempt. Replays are free.
4. Solve it or reveal the answer after four attempts. Start another round whenever you like.

Songs are shuffled without repetition until the deck is exhausted. Excerpts always start at the beginning; silence in the original remains silence. Unsupported or removed files show an error; skip or refresh the library. Audio stops when the app leaves the foreground, loses audio focus or headphones disconnect. Rotation preserves the current question and session score; scores are not permanently stored.

The short durations are intentionally harder than Heardle's six-stage 1/2/4/7/11/16-second format. Playback cutoffs use Android's main-thread timer and MediaPlayer, so 150 ms is approximate, especially over Bluetooth. Device testing is required; this is not sample-accurate audio trimming.

## Install and update

Download **PocketBlindtest.apk** from a GitHub release and allow Android to install it. Use release APKs for normal installation: debug CI artifacts have a different signature and cannot update release installs.

All gameplay works in airplane mode. Only tapping **Check for updates** contacts GitHub. When the repository is public, this checks the latest stable release, downloads its APK on confirmation, and opens Android's installer after another tap when the download completes. Android always asks for installation approval. The system verifies package identity and signature when updating.

While this repository is private, the anonymous update API cannot see releases. Download updates from GitHub in a signed-in browser instead. No GitHub token is embedded in the app. Making the repository public later enables the existing updater without an app change. Repository owner/name and APK asset name must remain stable (or the app must be updated first).

## CI and releases

No local build is needed or was run during creation. GitHub Actions runs debug assembly and Android lint for pushes and PRs. Java 17, Gradle 8.13 (checksum-verified wrapper), AGP 8.13.2 and Android SDK 36 are pinned. There is no Kotlin compiler, Compose compiler or third-party app dependency.

Use Conventional Commits, especially squash PR titles:

- `feat: add random starting points`
- `fix: handle missing music files`
- `docs: explain installation`

Release Please follows the MyLists pattern: changes on `main` maintain a version PR. Merge that PR to create a `vX.Y.Z` tag and GitHub release. The same workflow checks out that tag, builds/lints a signed release, and uploads the APK plus SHA-256 checksum. It does not rely on a tag-triggered workflow (GitHub's default token does not trigger those). `version.txt` is the source of truth. Android versionCode is `major * 1000000 + minor * 1000 + patch` (minor/patch must remain below 1000).

Required repository Actions secrets:

- `SIGNING_KEYSTORE_BASE64`: base64 of the persistent release keystore.
- `SIGNING_STORE_PASSWORD` and `SIGNING_KEY_PASSWORD`: its passwords.

The alias is `pocketblindtest`. Keep an independent secure backup of the key and passwords: losing them prevents updates to existing installations. Never commit a keystore. Repository Actions must be allowed to create PRs. As with MyLists, PRs created by the default GitHub token may need a manual CI dispatch or a human push to run PR checks; the release job itself always builds and lints the tagged code.

The initial version is 0.1.0; the first feature commit after bootstrap creates the next release PR. APK compilation and on-device validation happen only through CI and the phone, not in the creation workspace.

## Device smoke test

- Test permission allow/deny/settings recovery and an empty library.
- Try all four durations with speaker and Bluetooth; check intros with silence.
- Check wrong guess, correct guess, skip, replay and final reveal.
- Rotate mid-round; background the app or unplug headphones during playback.
- Test airplane-mode gameplay and offline update failure.
- Install two successive signed release APKs to verify in-place updates.
