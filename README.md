# Pocket Blindtest

A simple music guessing game for Android 16. Play with songs stored on your phone, fully offline. No ads, accounts or analytics.

## How to play

1. Allow access to your music library.
2. Listen to the intro and search for your answer by title or artist.
3. Guess the song in four attempts: **0.15 → 0.5 → 2 → 5 seconds**.

A wrong guess or skip unlocks a longer clip. Replay as often as you like. Songs are shuffled without repeating until you've played through your library.

Clips start at the beginning of each song, including any silence. The shortest clip is approximate, especially over Bluetooth.

## Install

Download **PocketBlindtest.apk** from the [latest release](https://github.com/vincedelmas/PocketBlindtest/releases/latest), open it and allow Android to install it.

In the app, tap **Check for updates** to download a new version. Android asks you to confirm installation. Only the update feature uses the internet; gameplay stays offline.

Built for a Nothing Phone (3a) running Android 16. Other devices have not been tested.

## Development

Native Java with Android's built-in UI and audio APIs. No third-party app dependencies.

Builds run only in GitHub Actions after merging a Release Please version pull request. The release job builds and lints the signed APK, then uploads it to the GitHub release.

Use [Conventional Commits](https://www.conventionalcommits.org/), such as `feat: add a game mode` or `fix: handle missing songs`. Release Please opens a version pull request from changes on `main`. Merging it creates a release and triggers the signed APK build and upload.

### Release signing

The release workflow needs these repository secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`

The key alias is `pocketblindtest`. Keep a secure backup of the signing key and passwords: future updates must use the same key. Never commit them.
