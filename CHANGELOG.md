# Changelog

## 1.21.1-1.0.6

### Fixed

- Sped up `/mpa findConflicts` on large packs.

## 1.21.1-1.0.5

### Added

- `/mpa showoff structure <template>`, `/mpa showoff file <name>` and `/mpa showoff entity <entity> [nbt]`
  open an isometric preview of a structure template, a structure file from the structure grab folder,
  or an entity, with drag to rotate, right-drag to pan, scroll to zoom, a 16 colour or transparent
  background, and a screenshot button that saves only the preview.
  `angle`, `zoom`, `pan`, `reset`, `background`, `screenshot` and `close` subcommands do the same
  from commands, so a script or MCP server can drive it through `/execute as <player>`. Needs the
  mod on the client.

### Changed

- `/mpa opsword` no longer puts Knockback on the sword.

### Fixed

- `/mpa copy` run by a fake player, such as a mod's automation, no longer fails with an error; it
  prints the click-to-copy text instead.

## 1.21.1-1.0.4

### Added

- `/mpa structureGrab <from> <to> [name]` saves a region of the world as a structure file, taking
  its corners the way `/fill` does, or from `pos1`/`pos2` corners set while walking a build.
  Writes both `.nbt` and `.snbt` into `modpackassistant/structures/`.

### Changed

- The short command alias is now `/mpa` instead of `/ma`, which clashed with Mystical Agriculture.

## 1.21.1-1.0.3

### Changed

- Changed `/ma copy` output to use a quantity of 1 by default while retaining larger stack counts and
  item components ([#1](https://github.com/breakinblocks/Modpack-Assistant/issues/1)).

### Fixed

- Prevented read-only region scans from generating chunks and set a bounds for block-search results.
- Added loot-table dependency validation before simulation and excluded unsafe functions and global loot modifiers.
- Restored vanilla message fallbacks, argument suggestions, and lowercase aliases.
- Corrected shapeless matching, mirrored shaped recipes, and shaped/shapeless conflict detection.
- Made recipe comparisons, entity removal, and structure-loot testing bounded and cancellable.
- Corrected spawn simulation to honor declared pack sizes and recheck loaded chunks during simulation.
- Added rejection of oversized clipboard exports before network encoding.
- Required safe supported dimension-teleport destinations without clearing blocks.
- Preserved component mining drops and prevented report-file overwrites.

## 1.21.1-1.0.2

### Added

- Added `/ma locateBlock <block> <chunk_radius>` to find every placement of a block across a chunk region,
  list the nearest ten in chat with click-to-teleport coordinates, and write the full list to
  `logs/modpackassistant/blocks/`. Handy for checking that a worldgen feature is actually placing.

## 1.21.1-1.0.1

### Fixed

- Corrected `/ma testStructureLoot` to take the structure as a registry key argument, matching vanilla `/place structure`.

## 1.21.1-1.0.0

Initial Release
