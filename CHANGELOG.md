# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Project by:** djsaintg (concept & direction) · Claude/Anthropic (development)

---

## [0.2.0] — 2026-10-25

### Added
- **Waypoint arrow** on the guide panel: follows the camera every world tick (64 directions) and
  shades from green when you face the target to red when it's behind you.
- **Map and compass marker** for the current target.
- Steps can name blocks to find (`find` / `findLabel` in the guide JSON): loose sticks, bushes,
  rubble, trees, stone, every ore, and the bench to craft at.
- `/guide waypoint` to turn waypoints on or off.
- The jar now carries a small asset pack (arrow images and `GuideArrow.ui`).

### Changed
- The guide panel fits its contents instead of stretching to the bottom of the screen.
- `gradle installMod` / `runServer` remove older ObjectiveTracker jars first.
- Hytale install paths are detected automatically (override in `local.properties`).

### Fixed
- Plant Fiber guidance: grass drops nothing; bushes and tree leaves do.

---

## [0.1.0] — 2026-10-25

### Added
- Zygor-style **survival guide panel**: 40 steps in 8 chapters, from sticks and rubble to an
  Adamantite Pickaxe, built from the game's own recipes, drop tables and ore placement (0.7.4).
- Objectives that complete themselves from your inventory, zone and depth, with `skipIf` so
  experienced players aren't sent back for sticks.
- `/guide` commands: show, hide, done, skip, back, step, list, reset, help.
- Per-player progress saved to disk; customisable guide via `survival.json`.
