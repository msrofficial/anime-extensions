# Contributing

Contributions are welcome when they are focused, tested, and limited to sources maintained in this repository.

## Scope

This repo currently keeps only MSR-maintained extensions:

- `src/hi/toonstream`
- `src/hi/animedubhindi`
- `src/hi/moviebox`

Do not re-add a large unrelated extension set. New sources should be added one by one after they are checked and tested.

## Requirements

- Kotlin/Android basics
- Aniyomi extension API basics
- OkHttp/JSoup or JSON API parsing
- A working local Gradle environment

## Before Submitting

Run the smallest useful check for the extension you changed:

```sh
./gradlew --no-daemon :src:hi:<module>:compileDebugKotlin
```

For APK testing:

```sh
./gradlew --no-daemon :src:hi:<module>:assembleDebug
```

## Pull Request Checklist

- The change is limited to one source unless shared code is truly needed.
- Version code is bumped when behavior changes.
- Search, details, episode list, and video playback were checked.
- Existing user-updatable package/signature behavior is preserved.
- No unrelated generated files or local build output are committed.
