# RetroCrate — Roadmap to v1 (working app)

The path from today's empty skeleton to a real, usable RetroCrate. Each branch is one PR, ships one vertical slice end-to-end, and leaves the app in a runnable state. No mid-air refactors, no half-finished layers.

> **Update this doc** whenever a branch lands (mark ✅), scope shifts, or a decision resolves an open question.

---

## Definition of "v1 working"

On the user's Android device, after install:

1. Open app → see a Steam-style discovery feed populated with **real games from a real ROM source**.
2. Tap a game → full detail screen with art, metadata, description, and source list.
3. Tap **Install** → the file actually downloads to a known location, with progress visible and a notification.
4. Open **Library** → the downloaded game appears with cover art.
5. Tap the library entry → opens the ROM in the user's emulator (via `Intent.ACTION_VIEW`) or reveals it in the filesystem.
6. Open **Search** → query by title, filter by platform.
7. Open **Settings** → view sources, storage location, version.

If all seven flows work end-to-end without manual file shuffling, v1 is done.

---

## Strategy

- **Vertical slices, not horizontal layers.** Every branch ships a feature end-to-end (UI + data + storage). No "data layer only" PRs that don't show anything.
- **Fakes before real data.** Layout/feel gets validated against fake data before we wire a real scraper. Cheaper to iterate on visuals than to debug HTML parsing while also tweaking UI.
- **One ROM source for v1.** Multi-source comes post-v1. Recommendation: **Myrient** — clean directory listings, no JS, no auth, well-organized per platform, tolerant of light scraping. Decision needed (see Open decisions).
- **One platform target for the very first scrape.** Recommendation: **Game Boy Advance** — small ROMs (~10–30 MB), huge library, good test coverage for the download path without burning bandwidth. The architecture is platform-agnostic; the first scrape just proves the pattern.
- **Hilt and Room stay deferred.** Both blocked on AGP 9 ecosystem catch-up. Substitutes: plain `viewModel()` for DI, in-memory caches + DataStore for persistence. Revival is a post-v1 branch.
- **HIG-behavior sweep on every PR.** Clarity / Deference / Depth / Consistency / Feedback / Accessibility / Delight — explicit walkthrough in each PR description.
- **Doc-and-code ship together.** Per LLM-CONTEXT.md update protocol — every PR that changes architecture/features/conventions/dependencies updates the relevant LLM-CONTEXT sections in the same commit set.

---

## Branches

### ✅ Branch 0 — `main` (foundation, shipped)

Today's state. Renamed package, Steam-themed design system, 6 stub screens behind bottom nav, build green.

---

### Branch 1 — `feat/discovery-with-fakes`

**Goal:** Home screen looks like Steam — hero carousel, multiple horizontal rails of game capsules — using hardcoded fake data. No network. This is the visual milestone that proves the Big Picture aesthetic on a phone.

**Commits:**
1. `data: add FakeGameCatalog with ~30 real game titles across 5 platforms, with real public box-art URLs (e.g., libretro thumbnails repo)`
2. `domain: extend Game with releaseDate, developer, publisher, tags`
3. `core/ui: add GameCapsule composable (Steam-style 460×215 ratio, 12dp corners, cyan focus glow on hover/press)`
4. `core/ui: add GameRail (LazyRow with SectionHeader, 16dp padding, snap-on-fling)`
5. `core/ui: add HeroCarousel (auto-rotating, 16:9 with title overlay + gradient scrim)`
6. `data: add GameRepository interface + FakeGameRepository implementation`
7. `feature/home: replace EmptyState with HeroCarousel + 3 rails — Featured, Recently Added, Popular Retro`
8. `docs: update LLM-CONTEXT.md with the rail/capsule patterns + Coil image loading conventions`

**Acceptance:**
- Home looks like a Steam storefront on first glance.
- Cover art loads from the network (Coil over OkHttp).
- Scrolling Home is smooth (60fps on a mid-range device).
- HIG-behavior sweep: Clarity (clear sectioning), Feedback (press states), Accessibility (`contentDescription` on every capsule = game title), Delight (cyan glow on press, smooth carousel rotation).

---

### Branch 2 — `feat/game-detail-with-fakes`

**Goal:** Tap any capsule → full detail screen with hero art, description, screenshots, source list, **Install** CTA (still stubbed). Validates the detail layout against the Steam store-page reference.

**Commits:**
1. `core/ui: add SectionHeader, MetadataChip (platform/year/size), ScreenshotCarousel`
2. `feature/detail: rebuild GameDetailScreen with hero image + parallax scroll, title, metadata row, description, screenshot strip, source list, sticky bottom Install button (Steam green)`
3. `navigation: wire Home → Detail with shared-element-style fade-through (placeholder until shared elements done properly)`
4. `feature/detail: stub Install button to log + snackbar "download queued (not yet implemented)"`
5. `domain: enrich Game with screenshots, developer, publisher`
6. `data: extend FakeGameRepository with rich detail per game`
7. `docs: update LLM-CONTEXT.md detail patterns section`

**Acceptance:**
- Detail feels like Steam's store page.
- Install button is Steam green (secondary color), prominent, sticky at bottom edge.
- HIG-behavior sweep: Depth (parallax + sticky CTA), Feedback (snackbar on Install), Accessibility (every screenshot has `contentDescription`).

---

### Branch 3 — `feat/myrient-source` *(decision needed before starting — see Open decisions)*

**Goal:** Replace `FakeGameRepository` with a real scraper. ONE source, ONE platform (recommended: Myrient + GBA). Discovery feed and detail screen now show real games.

**Commits:**
1. `data/network: add OkHttp client config (10s timeouts, custom user-agent, disk cache 50MB, per-host rate limiter ≤2 concurrent)`
2. `data/source: add RomSource interface (search, fetchDetail, resolveDownloadUrl)`
3. `data/source/myrient: add MyrientSource implementing RomSource — Jsoup parsers for the directory listing pages`
4. `data/source/myrient: add MyrientSourceTest with HTML fixtures (no network)`
5. `data: replace FakeGameRepository with MyrientGameRepository (real scrape, in-memory cache, DataStore-backed catalog snapshot for offline open)`
6. `feature/home: switch rails to "Recently Added", "Popular This Week" (derived from source signals), "Browse by Platform"`
7. `core/common: add MetadataEnricher — when a game has no box art / description from the source, lazy-fetch from a stable metadata index (e.g., libretro thumbnails)`
8. `docs: update LLM-CONTEXT.md §13 scraping strategy with Myrient specifics, add to decision log`

**Acceptance:**
- Home shows real Myrient GBA games.
- Detail loads real Myrient detail page when tapped.
- All scrapes are user-initiated (open Home / tap card / search). No background traffic.
- Tests for Myrient parser pass without network.
- HIG-behavior sweep: Feedback (loading states everywhere; specific error messages on failure with retry), Accessibility (no scroll loop traps; TalkBack works).

---

### Branch 4 — `feat/search`

**Goal:** Search tab functional. Type a query → debounced results across the source. Filter by platform.

**Commits:**
1. `feature/search: SearchScreen with TextField (Steam-style — cyan underline, search icon), FilterChips row for platforms, results LazyColumn`
2. `feature/search: SearchViewModel with debounced query (300ms), StateFlow<SearchUiState> covering Idle / Searching / Results / Empty / Error`
3. `data: GameRepository.search(query, platform) — uses MyrientSource.search`
4. `core/ui: SearchEmptyState, SearchErrorState with retry`
5. `feature/search: recent-searches list (DataStore-backed)`

**Acceptance:**
- Typing in Search returns real results in <1s for cached queries, <3s for fresh.
- Platform filter chips toggle correctly with cyan selection state.
- Recent searches show on empty query.
- HIG-behavior sweep: Clarity (one input, clear filter chips), Feedback (loading spinner, empty/error states), Accessibility (search field labeled, results list announces count to TalkBack).

---

### Branch 5 — `feat/downloads`

**Goal:** **Install button actually downloads the ROM.** This is the hardest branch — survives process death, shows progress, handles failures.

**Commits:**
1. `data/download: DownloadRepository interface — enqueue, observe, cancel, retry`
2. `data/download/RomDownloadWorker: WorkManager Worker using Android's DownloadManager for the actual transfer (handles retries, network changes)`
3. `data/download/DownloadStateRepository: in-memory + DataStore-backed map of gameId → DownloadState, exposed as Flow`
4. `feature/detail: wire Install button → DownloadRepository.enqueue; button morphs into "Installing… 42%" with cyan progress bar (Steam install style)`
5. `feature/downloads: DownloadsScreen lists active + recent downloads with progress bars, pause/resume/cancel/retry buttons`
6. `notifications: foreground notification with progress while any download is active (Steam-blue accent, "Installing X" template, tap → open Downloads screen)`
7. `permissions: handle POST_NOTIFICATIONS runtime permission on Android 13+ (HIG Feedback — progress must be visible)`
8. `data: write downloads to app-specific external dir for v1 (see Open decisions for the SAF question)`

**Acceptance:**
- Tapping Install on a GBA ROM (~5MB) downloads, completes, and the file is visible in the app's files dir.
- Process death mid-download → resumes on next launch (WorkManager survives).
- Cancel works. Retry works.
- Notification shows for the duration with accurate progress.
- HIG-behavior sweep: Feedback (every state is visible: queued, downloading %, paused, failed reason, completed), Accessibility (progress announced as percentage to TalkBack; notification has clear title + action).

---

### Branch 6 — `feat/library`

**Goal:** Downloaded games show in the Library tab. Tap → open in emulator.

**Commits:**
1. `data/library: LibraryRepository — list of installed gameIds + file paths, DataStore-backed for v1`
2. `feature/library: LibraryScreen grid (2 columns portrait, 3 landscape, large cover capsules — Big Picture density)`
3. `feature/library: "Recently Installed" rail above the grid (Steam library pattern)`
4. `feature/library: tap card → "Play" sheet with two options: Open in emulator (Intent.ACTION_VIEW with content URI via FileProvider) or Reveal in files (Intent.ACTION_VIEW DOCUMENT_TREE)`
5. `feature/detail: when a game is already installed, swap Install button for Play button (Steam green → blue)`
6. `data: FileProvider configured in manifest with authority + paths xml`
7. `feature/library: long-press → Uninstall (with snackbar + undo — HIG Feedback)`

**Acceptance:**
- Library shows every successfully downloaded game.
- Tap → Play opens in the user's chosen emulator (the system intent picker handles emulator selection on first use).
- Uninstall removes file + library entry, with undo working for 5s.
- HIG-behavior sweep: Clarity (grid is scannable), Depth (Play sheet uses surfaceContainerHigh), Feedback (haptic on tap, snackbar on uninstall, undo).

---

### Branch 7 — `feat/settings`

**Goal:** Settings tab populated. Storage location, source list, about.

**Commits:**
1. `data/settings: SettingsRepository — DataStore-backed (storageDir, enabledSources, theme overrides if any)`
2. `feature/settings: SettingsScreen with sections — Storage, Sources, About`
3. `feature/settings: Storage section — current location, "Change…" opens SAF tree picker, "Open in files" intent`
4. `feature/settings: Sources section — list of sources with enabled toggle (just Myrient for v1, but the UI is ready for more)`
5. `feature/settings: About — version, build, link to repo`

**Acceptance:**
- Changing storage location actually moves new downloads there (existing files stay; user is warned).
- Disabling Myrient hides it from search/discovery (effectively making the app empty — that's fine, it's a switch the user controls).
- HIG-behavior sweep: Clarity (grouped sections), Consistency (one row pattern for all toggles).

---

### Branch 8 — `feat/polish-v1`

**Goal:** Make it feel finished. This is the "you'd be proud to show someone" branch (even though no one will see it).

**Commits:**
1. `splash: androidx.core.splashscreen with Steam navy backdrop + RetroCrate wordmark in Steam cyan`
2. `branding: add a simple "RetroCrate" wordmark in top-left of Home and Library top app bars (Steam pattern)`
3. `font: bundle Inter as the app font family (closest free analogue to Steam's Motiva Sans)`
4. `haptics: LocalHapticFeedback on every commit action — Install press, Play press, Search submit, Uninstall confirm`
5. `motion: container transforms on Home → Detail (proper shared-element via Compose's SharedTransitionScope)`
6. `empty states: tune copy + iconography across every empty state for personality`
7. `error states: every error has a specific message + retry CTA + "report to logs" affordance (logs to local file, no network)`
8. `accessibility audit: run TalkBack through every flow; fix any missing contentDescriptions or trap loops`
9. `performance pass: confirm 60fps on the user's device across Home scroll, Search typing, Library grid`
10. `docs: update LLM-CONTEXT.md decision log with v1 ship`

**Acceptance:**
- Splash → Home transition is smooth, branded, no white-flash.
- TalkBack-only navigation of every flow works.
- 60fps confirmed.
- HIG-behavior sweep: Delight (motion, splash, haptics), Accessibility (TalkBack-clean), Consistency (Inter everywhere, one wordmark, one motion spec).

---

## 🎯 v1 ships when Branch 8 lands

That's the working-app milestone. Demoable, useful, the user's actual workflow.

---

## Post-v1 backlog (no fixed order)

- `feat/hilt-revival` — re-introduce Hilt when AGP 9 compat lands. Refactor ViewModels to constructor injection, add modules.
- `feat/room-revival` — re-introduce Room. Migrate the DataStore-backed catalog/library/downloads to Room with proper queries.
- `feat/source-vimms` — add Vimm's Lair as a second source.
- `feat/source-archive-org` — add Internet Archive collections.
- `feat/wishlist` — Steam-style wishlist with "available now" notifications.
- `feat/discovery-queue` — Steam's randomized 12-game queue.
- `feat/screenshot-gallery` — full-screen swipeable gallery in Detail.
- `feat/emulator-deep-links` — recognize installed emulators (Citra, Dolphin, DuckStation, MyOldBoy, etc.) and offer them by name in the Play sheet.
- `feat/big-picture-mode` — true TV/Deck-style layout when running on a tablet/large screen with a controller.
- `feat/playtime-tracking` — log when ROMs are opened, show "Last played" on library cards (Steam pattern).
- `feat/notes-and-tags` — user notes per game, custom tags.
- `feat/backup-restore` — export/import library state as JSON.

---

## Open decisions (resolve before the named branch)

| # | Question | Blocks | Recommendation |
|---|---|---|---|
| 1 | **Which ROM source for v1?** | Branch 3 | Myrient — clean HTML, no JS, well-organized, scrape-tolerant. |
| 2 | **Which platform to scrape first?** | Branch 3 | GBA — small ROMs (5–30MB), huge library, lots of games the user owns. |
| 3 | **Storage location default — app-specific dir, or SAF tree picker?** | Branch 5 | App-specific external dir for v1 (zero permission UX, simplest). Add SAF picker in Branch 7 settings. Caveat: app uninstall wipes the files. |
| 4 | **Emulator handoff — Intent.ACTION_VIEW (system picker) or hardcode known emulators?** | Branch 6 | ACTION_VIEW + content URI via FileProvider for v1. Hardcoded deep links is a post-v1 polish. |
| 5 | **What metadata index to enrich Myrient's bare directory listings (box art, descriptions, screenshots)?** | Branch 3 | libretro-thumbnails repo on GitHub (predictable URL pattern per ROM filename, no API needed). Descriptions can come from a small bundled JSON of "popular titles" or be left blank for v1. |
| 6 | **Discovery rails — what signals do we have from Myrient (recent additions, sizes, etc.) vs. what do we hand-curate?** | Branch 3 | Mix: "Recently Added" from Myrient directory mtimes; "Browse by Platform" from filesystem; "Featured" from a small bundled local JSON the user can edit (decision log curation, Steam-staff-pick vibe). |
| 7 | **Should we add an in-repo `data/` JSON of "user's curated library" to seed featured rails, or pull from the user's existing emulator library on first launch?** | Branch 1 (fakes) and Branch 8 (real) | Bundled JSON, hand-edited. Single-user app — manual curation is faster than auto-detection. |
| 8 | **Notification importance level?** | Branch 5 | DEFAULT — visible, no sound. The user wants progress visible, not interrupted. |

---

## How to use this doc

- Before starting a branch, **read the branch section + the open decisions it blocks on**. If a blocking decision isn't resolved, ask the user first.
- After the branch lands, **mark it ✅ and add the actual commit SHAs underneath** (or just the date). Move resolved decisions out of the table and into the LLM-CONTEXT.md decision log.
- If scope shifts mid-branch (it will), **update this doc in the same PR** as the change. Same protocol as LLM-CONTEXT.md.
- If a new feature idea shows up that doesn't belong to a current branch, **add it to Post-v1 backlog**. Don't sneak it into an open branch.
