# RetroCrate — Roadmap

Where RetroCrate is, and where it's going. The original "path to v1" roadmap is **done** — the app is a working v2.x with a live catalog, discovery Home, search, dynamic collections, a manual source picker, and real downloads across ~29 platforms. This doc now tracks the path from "works" to **launch-ready and reference-quality** — the bar a product worth real money would clear.

> **Update this doc** whenever a track lands, scope shifts, or a decision resolves an open question. Same protocol as [LLM-CONTEXT.md](LLM-CONTEXT.md) — doc and code ship together.

---

## What "launch-ready" means here

This is a **single-user, private, never-distributed** personal-backup tool (see [LLM-CONTEXT.md §5](LLM-CONTEXT.md)). "Launch-ready / million-dollar quality" is therefore about **quality, not reach**: the app should be as reliable, fast, and delightful as something you'd pay for — for its one user, on the Retroid Pocket 5, every single time.

The bar, concretely:

1. **It never silently fails.** Every download either succeeds, or fails with a specific, actionable message and a retry path. No dead ends, no "no results" for a game that's actually there, no OOM.
2. **It finds what you own.** If a game exists on any configured source, RetroCrate finds it and gets it — including big, split, and encrypted-format releases.
3. **It's fast.** Cold start to usable Home in seconds; search feels instant; no jank; no memory pressure.
4. **It delights.** Discovery pulls you toward games you forgot you owned; the whole thing feels like Steam Big Picture, not a scraper with a UI.
5. **It heals itself.** When a source's HTML drifts (it will), the app degrades gracefully, says so, and is trivial to diagnose from logcat.

> **On distribution:** the "launch with no issues" goal is interpreted as *launch-grade quality for personal use*. Public distribution of a ROM-download app carries real legal exposure and contradicts §5 — recommended to stay private. If that changes, it's a user decision and a dedicated track, not a silent pivot.

---

## Current state (honest assessment)

**Solid:**
- Catalog: ~33k games / 29 platforms, live titledb Switch sync, OpenVGDB + libretro metadata, dynamic Collections, submit-driven search with smart ranking.
- Discovery Home: live hero carousel, popularity feed, browse-by-platform/genre, all data-driven (no hardcoded lists).
- Downloads: Vimm's (primary, exact-match) → Debrid (optional) → Internet Archive (fallback), plus NXBrew DDL (picker-only, Switch, with game disambiguation + Base/Update/DLC sections). Manual source picker. Foreground-service downloads survive screen-off **through extraction**. Terminal completion/failure notifications. Archive extraction (zip/tar/7z/rar, disc sets, `.m3u` generation).
- **Download reliability just hardened (2026-07-01):** Vimm's `.7z` OOM fixed (native engine + largeHeap), 3DS `.cci` recognized, NXBrew page-matching fixed — all verified on-device.
- **Background-extraction corruption fixed (2026-07-02):** the foreground service stopped mid-extract (extraction runs under `Preparing`, which wasn't counted as active) → screen-off froze it → corrupt ROM + undeleted temp archive; now the service stays alive through `Preparing` and posts a completion notification.

**Fragile / gaps (the roadmap targets these):**
- **No resume.** A dropped connection restarts a multi-GB download from zero.
- **No integrity check.** We trust the file is correct and uncorrupted; No-Intro/Redump hashes aren't verified.
- **No multi-part joining.** NXBrew's big releases (`Part1`/`Part2` across Base/Update/DLC) and split `.001/.002` archives don't assemble.
- **Startup cost.** Cold launch churns ~200 MB of GC building the catalog; the titledb parse is heavy. `largeHeap` masks it; Room would fix it.
- **Source drift is invisible until you try.** No health check; a broken source is discovered only mid-download.
- **Emulator handoff is manual.** Downloads land in a folder; the user opens them in RetroArch/ES-DE by hand.
- **Hilt + Room still deferred** (now unblocked on AGP 9 — see §14 decision log).

---

## Roadmap — themed tracks, roughly prioritized

Prioritized by impact on the five "launch-ready" criteria. Each track is a set of PR-sized slices; take them in any order within a track.

### Track A — Download reliability & coverage (highest impact)
*The core value. If a download can fail, it must fail loudly and recoverably; if a game exists, we must get it.*

- **A1 — Resumable downloads.** HTTP `Range` requests + persisted byte offset so a dropped transfer resumes instead of restarting. Critical for multi-GB Switch/3DS/disc images over Vimm's throttled tier. (Vimm's `dl*.vimm.net` already answers `206 Partial Content`.)
- **A2 — Integrity verification.** *(Started 2026-07-02: `NspIntegrity` rejects a truncated Switch `.nsp` by parsing its PFS0 table; native/streaming paths also reject a transfer short of Content-Length / declared archive size — catches the "no bootable game present" truncation class.)* Remaining: verify against the No-Intro/Redump CRC/MD5 where the source exposes it (Vimm's shows it; OpenVGDB stores hashes). Show a green "verified" badge; warn on mismatch. Turns "probably right" into "provably right."
- **A3 — Multi-part / split-archive joining.** Assemble `Part1`/`Part2` (NXBrew) and `.001/.002`/`.7z.001` sets before extraction. Unlocks big first-party Switch/Wii U releases that currently can't complete.
- **A4 — Update + DLC awareness (Switch).** *(Partly done 2026-07-02: the NXBrew picker now lists Base + Update + DLC as separate, downloadable sections.)* Remaining: fetch and **co-locate** them with the naming the emulator expects (and ideally auto-offer the update/DLC alongside the base) so a game arrives complete in one flow, not three manual picks.
- **A5 — Every failure branch logs + surfaces.** Audit the download path so no failure updates the UI without a log line (the 3DS `.cci` bug hid here). Standardize on specific, actionable messages.
- **A6 — Smarter Auto chain.** When the top source resolves a file that fails verification or extraction, fall through to the next source automatically instead of surfacing the failure.

### Track B — Efficiency & performance
*Cold start in seconds, instant search, no memory pressure. "Efficient and effective."*

- **B1 — Room revival (unblocks the rest).** Migrate the DataStore-JSON catalog/library/history stores to Room (KSP now works on AGP 9.2.1 with `android.disallowKotlinSourceSets=false` — verified, see §14). Query the catalog instead of loading 33k games into memory on every cold start — kills the ~200 MB startup GC churn.
- **B2 — Indexed search.** Back search with a Room FTS index instead of ranking 33k in-memory objects per query. Sub-frame results even as the catalog grows.
- **B3 — Lazy platform loading.** Load a platform's catalog when its rail/browse is first shown, not all 29 up front.
- **B4 — Streamed titledb ingest → Room.** Parse the 86 MB `US.en.json` straight into Room rows (already stream-parsed; land it in the DB, not a giant JSON cache).
- **B5 — Hilt revival.** Constructor injection for ViewModels/repositories; `hiltViewModel()`; `hilt-work` for WorkManager. Cleans up the `object`-singleton substitutes.

### Track C — Discovery & delight (the Steam magic)
*This is the "million-dollar idea" surface — the reason it feels like a storefront, not a scraper.*

- **C1 — Wishlist "available now."** The wishlist exists; add a background (user-triggered) check that flags when a wishlisted game becomes resolvable on a source, Steam-style.
- **C2 — Discovery Queue.** Steam's signature: a swipeable queue of N games you haven't seen, biased by the platforms/genres in your library. Turns browsing into a session.
- **C3 — "Because you own…" recommendations.** Use the on-device library + Collections to recommend franchise siblings and same-developer titles.
- **C4 — Richer detail.** Screenshot gallery polish, "also on these platforms," file-size/region clarity, verified-dump badge (from A2).
- **C5 — Motion & haptics pass.** Shared-element Home→Detail transform, install-button progress morph, haptics on commit actions. Earned delight, not slather.

### Track D — Library & emulator handoff
*Close the loop: from "downloaded" to "playing" without leaving the app.*

- **D1 — Open-in-emulator.** `Intent.ACTION_VIEW` with a FileProvider content URI so a downloaded ROM opens directly in RetroArch / the RP5 launcher. Detect installed emulators and offer them by name.
- **D2 — On-device library polish.** The "On device" view already browses SAF folders; add cover-matched cards, sizes, and quick delete.
- **D3 — Playtime / last-played** (optional, Steam pattern) — log opens, surface "Last played" on library cards.

### Track E — Resilience & observability
*Sources drift. The app should notice, say so, and be trivial to fix.*

- **E1 — Source health check.** A user-triggered "Test sources" action (Settings) that resolves a known game per source and reports up/down/drifted — so drift is caught before a real download fails.
- **E2 — Structured drift logging.** Standardize the "log loudly" pattern (already used for Vimm's/NXBrew) across all sources: fetch status, parse counts, why a match failed.
- **E3 — Parser fixtures from real captures.** Keep a small set of real (sanitized) HTML fixtures per source so a drift regression is caught in tests, not on-device.
- **E4 — Graceful multi-source degradation.** If a source is down, hide it from the picker with a reason rather than showing an empty result.

### Track F — Polish & correctness
- **F1 — Search input robustness.** "mario64" (no space) missing "Super Mario 64" is a ranking gap — fold digit/letter boundaries so joined queries hit.
- **F2 — Empty/error-state copy pass.** Distinguish "not on this source" from "source error" everywhere (NXBrew's "no results" was ambiguous).
- **F3 — Accessibility sweep.** TalkBack pass across every flow; ≥56 dp targets audit; content descriptions.
- **F4 — Dependency hygiene.** Drop the now-unused `org.tukaani:xz` / Commons Compress 7z path if nothing else needs it (native engine owns 7z/rar now).

---

## The next 5 (recommended order)

Highest reliability-per-effort, building on today's download fixes:

1. **A1 Resumable downloads** — the single biggest reliability win for big files on a throttled source.
2. **A5 Failure-branch audit** — cheap, and guarantees no more silent "couldn't find a ROM"-class dead ends.
3. **A2 Integrity verification** — turns the whole download path from "trust" to "proof."
4. **E1 Source health check** — makes source drift a 10-second diagnosis instead of a mystery.
5. **B1 Room revival** — unblocks the performance track and kills the startup GC churn.

---

## Post-roadmap backlog (no fixed order)

- Multi-part joining for arbitrary split formats.
- Jackett/Prowlarr-backed indexer if the user self-hosts one (cleaner debrid matching).
- Additional torrent indexers (resilience against one going down).
- `feat/big-picture-mode` — true TV/Deck layout on a larger screen with a controller.
- Backup/restore of library + wishlist state as JSON.
- Notes & custom tags per game.

---

## How to use this doc

- Before starting a track slice, read it + any [LLM-CONTEXT.md §15](LLM-CONTEXT.md) open question it touches.
- When a slice lands, note it here and add a dated [LLM-CONTEXT.md §14](LLM-CONTEXT.md) decision-log entry in the same PR.
- New ideas that don't fit an active track go to the backlog — don't smuggle scope into an open slice.
