# Objective Tracker

A Zygor-style step-by-step **survival guide** for Hytale. A panel on the right side of the screen
tells you exactly what to do next, from picking up your first sticks and pebbles to crafting an
Adamantite Pickaxe, and ticks objectives off live as items land in your inventory.

```
CHAPTER 1 - FIRST STEPS
Gather sticks and plant fiber
Pick up loose sticks lying on the ground...
[X]  Sticks   6/6
[  ]  Plant Fiber   2/6
Tip: Forest edges near spawn have plenty of both.
Step 2 / 40   -   /guide help
```

## What it does

- **8 chapters, 40 steps**: Fieldcraft tools → Workbench → Furnace → Copper → Tanning/Linen → Iron →
  Tier 2 Workbench → Zone 2 Thorium → Zone 3 Cobalt → Tier 3 Workbench → Zone 4 Adamantite.
  Every recipe, item ID and ore location in it was taken from the game's own asset files (v0.7.4).
- **Auto-completion**: steps check your whole inventory once a second. Objectives can be
  - `item`: hold N of an item (or any of a list of items),
  - `resource`: hold N of anything tagged with a resource type (`Rubble`, `Wood_Trunk`, `Rock`, …),
  - `zone`: enter a zone (`Zone2`, `Zone3`, …),
  - `below`: go below a Y level,
  - `manual`: confirm it yourself with `/guide done` (e.g. "upgrade your workbench").
- **Smart skipping**: each step can have `skipIf` conditions, so a player who already has an iron
  pickaxe isn't told to go and collect sticks.
- **Progress is saved per player** (by UUID) and survives restarts.
- **No asset pack needed**: the HUD is sent as inline UI markup, the same way the game's built-in
  spectator HUD works.

## Commands

All players can use these (`/og` is an alias):

| Command | What it does |
|---|---|
| `/guide show` / `/guide hide` | Toggle the panel |
| `/guide done` | Finish the current step (for "manual" steps) |
| `/guide skip` / `/guide back` | Move one step forward / back |
| `/guide list` | List every step with your progress |
| `/guide step <n>` | Jump to step *n* |
| `/guide reset` | Start over |
| `/guide help` | Show this list in game |

## Building

Requirements: JDK 25 or newer, Gradle, and Hytale installed through the launcher.

```bash
gradle build          # -> build/libs/ObjectiveTracker-0.1.0.jar
gradle installMod     # builds and copies the jar into Hytale's UserData/Mods folder
gradle runServer      # starts a local dedicated server in ./run with the plugin loaded
```

The Hytale install and Mods folder default to the Flatpak launcher's locations on Linux
(`~/.var/app/com.hypixel.HytaleLauncher/...`). If yours are elsewhere, create a
`local.properties` file (it isn't committed) next to `build.gradle.kts`:

```properties
hytaleDir=/path/to/Hytale/install/release/package/game/latest
hytaleModsDir=/path/to/Hytale/UserData/Mods
```

## Testing in game

1. `gradle installMod`
2. Launch Hytale, create or open a world, and enable **ObjectiveTracker** in the world's mod settings
   if it isn't enabled automatically.
3. The guide panel should appear on the right after you spawn. Try `/guide list`.

Or connect to the dev server from `gradle runServer` (`localhost`). On first start you'll have to
authenticate it: type `/auth login device` in the server console.

## Customising the guide

On every start the plugin writes its bundled guide to
`<server>/mods/ObjectiveTracker_ObjectiveTracker/guides/survival.default.json` for reference.
To customise, copy it to `survival.json` in the same folder, edit, and restart the server. If
`survival.json` exists it replaces the bundled guide. Otherwise the bundled one is used, so
plugin updates bring guide fixes automatically. Unknown item IDs are listed as a warning in the
server log at startup.

Step format:

```json
{
  "id": "mine_copper",
  "title": "Find and mine Copper Ore",
  "text": "Shown under the title.",
  "hint": "Optional tip line.",
  "objectives": [
    { "type": "item", "ids": ["Ore_Copper", "Ingredient_Bar_Copper"], "count": 9, "label": "Copper Ore" }
  ],
  "skipIf": [
    { "type": "item", "ids": ["Tool_Pickaxe_Copper", "Tool_Pickaxe_Iron"], "count": 1 }
  ]
}
```

Step `id`s are what's saved in player progress, so keep them stable when editing.

## Project layout

```
src/main/java/dev/objectivetracker/
  ObjectiveTrackerPlugin.java   plugin entry: registers command, events, 1s ticker
  GuideService.java             evaluates objectives, advances steps, drives the HUD
  hud/GuideHud.java             the on-screen panel (CustomUIHud + inline markup)
  command/GuideCommand.java     /guide subcommands
  guide/                        guide data model + JSON loader
  progress/                     per-player progress (JSON files)
src/main/resources/
  manifest.json                 Hytale plugin manifest
  guides/survival.json          the survival guide
```

## License

[MIT](LICENSE) © 2026 djsaintg
