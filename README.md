# RetroCrate

A personal Android app for discovering and downloading retro game ROMs — a Steam-inspired storefront over public ROM sources, used by the repo owner to fetch backups of games he owns physically.

Private repo, single user, never distributed.

## Project context

- **[LLM-CONTEXT.md](LLM-CONTEXT.md)** — the canonical project context (mission, architecture, conventions, decisions, open questions). Read first.
- **[CLAUDE.md](CLAUDE.md)** — thin auto-load surface with the hard rules.

## Build & run

```powershell
.\gradlew assembleDebug        # build debug APK
.\gradlew installDebug         # install on connected device/emulator
.\gradlew lint                 # static analysis
.\gradlew testDebugUnitTest    # unit tests
```

(On Linux/Mac shells: `./gradlew …`.)

## Stack

Kotlin 2.2.10 · Jetpack Compose (BOM 2026.02.01) · Material 3 · Hilt · Navigation Compose · Room · Retrofit + OkHttp + kotlinx-serialization · Coil 3 · Jsoup · DataStore · WorkManager.

## Design philosophy

Enthusiastically faithful to Apple's [Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/), expressed through Material 3 on Android. See [LLM-CONTEXT.md §7](LLM-CONTEXT.md) for the seven-pillar rubric applied to every UI change.
