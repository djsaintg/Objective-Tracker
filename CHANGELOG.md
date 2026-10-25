# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Project by:** djsaintg (concept & direction) · Claude/Anthropic (development)

---

## [0.1.0] — 2026-10-25

### Added
- Zygor-style **survival guide panel**: 40 steps in 8 chapters, from sticks and rubble to an
  Adamantite Pickaxe, built from the game's own recipes, drop tables and ore placement (0.7.4).
- Objectives that complete themselves from your inventory, zone and depth, with `skipIf` so
  experienced players aren't sent back for sticks.
- `/guide` commands: show, hide, done, skip, back, step, list, reset, help.
- Per-player progress saved to disk; customisable guide via `survival.json`.
