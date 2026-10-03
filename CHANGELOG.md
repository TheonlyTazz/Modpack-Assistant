# Changelog

## 26.1.2-1.0.6

### Fixed

- Sped up `/mpa findConflicts` on large packs.

## 26.1.2-1.0.5

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

## 26.1.2-1.0.4

### Added

- `/mpa structureGrab <from> <to> [name]` saves a region of the world as a structure file, taking
  its corners the way `/fill` does, or from `pos1`/`pos2` corners set while walking a build.
  Writes both `.nbt` and `.snbt` into `modpackassistant/structures/`.

### Changed

- The short command alias is now `/mpa` instead of `/ma`, which clashed with Mystical Agriculture.

## 26.1.2-1.0.3

### Fixed

- Fixed analysis scans generating terrain and bounded retained block-search results.
- Fixed unsafe loot simulation functions, referenced resources, global modifiers, and excessive workloads.
- Fixed vanilla-client command arguments, English message fallbacks, and argument-free lowercase aliases.
- Corrected recipe matching for ingredient reassignment, mirrors, shaped/shapeless overlaps, and 26.1.2 empty-slot layouts.
- Fixed unsupported recipe displays being misclassified or interrupting scans
- Split recipe comparisons, entity removal, structure loot work, and registry reviews into cancellable scheduled batches.
- Fixed oversized clipboard exports and omitted redundant single-item quantities in plain output.
- Fixed unsafe teleport destinations and kept destination chunks active with temporary portal tickets.
- Fixed mining output losing item components.
- Fixed report filename collisions and prevented existing reports from being overwritten.
- Fixed partial structure-loot placement and cleanup records being lost on cancellation or failure.
- Fixed ore scans potentially accepting height bands entirely outside the dimension.
- Fixed drain jobs removing replacement fluids that no longer matched the selected fluid.
- Corrected obtainability reports to identify unreadable sources and qualify registered trade availability.

### Added

- Added regression tests

## 26.1.2-1.0.2

### Added

- `/ma locateBlock <block> <chunk_radius>` finds every placement of a block across a chunk region,
  lists the nearest ten in chat with click-to-teleport coordinates, and writes the full list to
  `logs/modpackassistant/blocks/`. Handy for checking that a worldgen feature is actually placing.

## 26.1.2-1.0.1

### Fixed

- `/ma testStructureLoot` now takes the structure as a registry key argument, matching vanilla `/place structure`. 

## 26.1.2-1.0.0

Initial Release
