# MSR Aniyomi/Anikku Anime Extensions

Source code for MSR anime extensions.

## Extension Repo URL

Add this URL in Aniyomi/Anikku:

```text
https://raw.githubusercontent.com/msrofficial/anime-repo/repo/index.min.json
```

## Repositories

- Source code: https://github.com/msrofficial/anime-extensions
- Built APK/index repo: https://github.com/msrofficial/anime-repo

## Build Locally

```sh
./gradlew --no-daemon :src:hi:toonstream:assembleDebug
```

## Publish Flow

Pushing source changes to `main` runs GitHub Actions, builds Toonstream, generates repo artifacts, and pushes them to `msrofficial/anime-repo` branch `repo`.
