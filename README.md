# MSR Anime Extensions

Source code for MSR's Aniyomi/Anikku extensions.

This repository is intentionally small. It only contains extensions maintained here; more sources will be added gradually after they are ported, tested, and published.

## Add The Extension Repo

Add this URL in Aniyomi/Anikku:

```text
https://raw.githubusercontent.com/msrofficial/anime-repo/repo/index.min.json
```

## Current Sources

| Source | Language | Module | Status |
| --- | --- | --- | --- |
| Toonstream | Hindi | `src/hi/toonstream` | Working |
| AnimeDubHindi | Hindi | `src/hi/animedubhindi` | Working |
| MovieBox | Multi | `src/hi/moviebox` | Working |

## Repositories

- Source code: [msrofficial/anime-extensions](https://github.com/msrofficial/anime-extensions)
- APK/index repository: [msrofficial/anime-repo](https://github.com/msrofficial/anime-repo)

## Build Locally

Build one extension:

```sh
./gradlew --no-daemon :src:hi:toonstream:assembleDebug
./gradlew --no-daemon :src:hi:animedubhindi:assembleDebug
./gradlew --no-daemon :src:hi:moviebox:assembleDebug
```

Compile Kotlin only:

```sh
./gradlew --no-daemon :src:hi:moviebox:compileDebugKotlin
```

## Publish Flow

Pushing source changes to `main` runs GitHub Actions.

The workflow:

1. Detects changed/new extension modules.
2. Builds only those modules when possible.
3. Signs release APKs with the repository signing key.
4. Publishes APKs, icons, `index.min.json`, and `repo.json` to `msrofficial/anime-repo` branch `repo`.

## Repository Layout

```text
src/hi/toonstream/       Toonstream extension
src/hi/animedubhindi/   AnimeDubHindi extension
src/hi/moviebox/        MovieBox extension
lib/                    Shared extractor libraries
core/                   Extension API/core helpers
.github/workflows/      Build and publish automation
```

## Notes

- Install APKs from the extension repo URL, not from source artifacts.
- Play Protect warnings can appear for newly signed third-party extension APKs.
- Keep the signing key unchanged so users can update installed extensions normally.
- If a source changes its website/API, open an issue or submit a focused pull request.
