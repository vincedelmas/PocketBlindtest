# Pocket Blindtest

A simple music guessing game for Android 16. Play with songs stored on your phone, fully offline. No ads, accounts or analytics.

## How to play

1. Allow access to your music library.
2. Choose infinite mode or a session of 5, 10, 15 or 20 songs.
3. Listen to the intro, search by title or artist, select a song and tap **Submit guess**.
4. Guess the song in six attempts: **1 → 2 → 4 → 7 → 11 → 16 seconds**.

Songs must be at least 16 seconds long.

A wrong guess or skip unlocks a longer clip. Replay as often as you like. Songs are shuffled without repeating until you've played through your library.

Clips skip up to ten seconds of leading silence. Each attempt starts at the same point, with its length measured in decoded audio samples. Bluetooth can delay when playback reaches your headphones.

## Sessions and statistics

Fixed sessions use distinct songs and finish with a summary. Infinite mode continues until you stop. You can resume the current session after closing the app.

Session and all-time statistics show your score, percentage, average clip length at a correct guess, and breakdowns by genre, five-year period and decade. Best and hardest categories need at least three results each. Missing music tags appear as **Unknown** and are excluded from rankings.

In the three-dot menu, **Replace unplayable song** asks for confirmation, then excludes the song for the rest of the session and replaces it without counting an attempt, a completed song or a result. This also works on the reveal screen and removes that round from saved statistics.

History uses a private SQLite database; the active game uses SharedPreferences. **Reset statistics…** in the three-dot menu deletes history and ends the active game after confirmation. It keeps your music files.

History stays on this phone across app updates. It starts with this version; older rounds cannot be recovered. Uninstalling the app clears it. Starting a new session keeps previous results in history.

## Install

Download **PocketBlindtest.apk** from the [latest release](https://github.com/vincedelmas/PocketBlindtest/releases/latest), open it and allow Android to install it.

In the app, tap **Check for updates** to download a new version. Android asks you to confirm installation. Only the update feature uses the internet; gameplay stays offline.

Built for a Nothing Phone (3a) running Android 16. Other devices have not been tested.

## Development

Java with Material 3 controls, RecyclerView song lists and Android audio APIs. Search filters off the UI thread and creates only visible song rows, so all matching tracks can be browsed.

Builds run only in GitHub Actions after merging a Release Please version pull request. The release job builds and lints the signed APK, then uploads it to the GitHub release.

Use [Conventional Commits](https://www.conventionalcommits.org/), such as `feat: add a game mode` or `fix: handle missing songs`. Release Please opens a version pull request from changes on `main`. Merging it creates a release and triggers the signed APK build and upload.

Check history queries without building the app: `python3 -m unittest discover -s tests -v`.

### Release signing

The release workflow needs these repository secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`

The key alias is `pocketblindtest`. Keep a secure backup of the signing key and passwords: future updates must use the same key. Never commit them.
