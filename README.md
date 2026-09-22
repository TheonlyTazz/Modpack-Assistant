# Modpack Assistant

Server-side commands for modpack development and server administration on NeoForge 26.1.2.
World inspection, region editing, loot and spawn simulation, recipe and tag audits, and the usual
admin conveniences. No blocks, items, or GUIs. Vanilla clients work with every feature except
automatic clipboard copying, which falls back to click-to-copy chat.

Every command is available as `/modpackassistant ...` or `/mpa ...`. Camel-case names also accept
their lowercase spelling (`/mpa scanores` works the same as `/mpa scanOres`).

## Commands

Admin and player (permission level 2 unless noted):

| Command | Purpose |
|---|---|
| `/toggledownfall` | Flip overworld weather between clear and rain |
| `/mpa devenv <true/false>` | Freeze or restore daylight, weather and mob spawning |
| `/mpa opsword` | Give a netherite sword enchanted to level 255 |
| `/mpa enchant add <enchantment> <0-255>` | Enchant the held item past normal limits |
| `/mpa enchant remove <enchantment>` | Strip one enchantment from the held item |
| `/mpa repair [player]` | Repair the held item |
| `/mpa heal [player]`, `/mpa feed [player]` | Restore health and hunger |
| `/mpa god [player]` | Toggle invulnerability |
| `/mpa nightvision` | Toggle permanent night vision |
| `/mpa tpd <dimension> [targets]` | Move entities to another dimension safely |
| `/mpa print <source>`, `/mpa hand`, `/mpa copy <source> [format]` | Item data to chat or clipboard (permission configurable) |

Plain-text copies (the default for `/mpa copy inventory` and other copy sources) leave out the quantity
for single items, for example `minecraft:stone`. Larger stacks retain it, for example
`2 minecraft:dirt`.

Clipboard exports are limited to 32,767 characters. Oversized output is rejected with a message;
copy fewer items or a smaller source. Vanilla clients receive English fallback messages and
the same command syntax and suggestions as modded clients.

`tpd` searches for dry, supported arrival space near the original X/Z coordinates, including
passenger clearance. It does not clear blocks. An entity stays where it is if no safe position
is found within two blocks horizontally and the destination's build height. A temporary portal
ticket keeps the arrival chunk active for cross-dimension entity transfers.

World editing:

| Command | Purpose |
|---|---|
| `/mpa clear <radius> [keep <ores/ores_and_modded/nothing> / remove <predicate>] [protect_bedrock]` | Mass-delete blocks across a chunk region |
| `/mpa drain [location] <radius>` | Flood-fill remove a connected body of fluid |
| `/mpa kill <type>`, `/mpa kill by <entity>` | Bulk entity removal |
| `/mpa minearea <radius> [harvest]` | Simulate mining every ore in a region and bank the drops in barrels |
| `/mpa testStructureLoot <structure> [samples]`, `... clear` | Chests of generated loot per structure loot table, with signs |
| `/mpa structureGrab <from> <to> [name] [both/nbt/snbt]` | Save a region of the world as a structure file |
| `/mpa structureGrab pos1 [pos]`, `pos2 [pos]`, `grab [name] [format]`, `clear` | Set the corners one at a time, then grab them |
| `/mpa cancel` | Abort the active long-running operation |

Analysis and reports, all read-only, each writing a file under `logs/modpackassistant/`:

| Command | Purpose |
|---|---|
| `/mpa scanOres <chunk_radius> [min_y] [max_y]` | Ore distribution by block and by height |
| `/mpa locateBlock <block> <chunk_radius>` | Count matching blocks and report the nearest retained matches, with click-to-teleport coordinates |
| `/mpa simulateLoot <iterations> <loot_table> [luck]` | Drop statistics for a loot table |
| `/mpa simulateSpawns <biome> <dimension> <ticks>` | Estimated natural spawning without placing entities |
| `/mpa findConflicts [type]` | Recipes that consume the same inputs |
| `/mpa findUncraftables [namespace]` | Items with no recipe, loot table, or trade source |
| `/mpa auditUnification [namespace]` | Material tags holding several items, or none |
| `/mpa exportTags <item/block/entity/fluid> [json/csv]` | Every registered object with its tags |
| `/mpa mapBiomes <radius> [interval] [y]` | Biome coverage over an area, without loading chunks |

Long-running operations run as jobs on the server tick, one every few ticks, and only one at a
time. They report progress and can be stopped with `/mpa cancel`.

Region radii are measured in chunks: `0` scans one chunk, and `n` covers `(2n + 1)` chunks per
side. Ore scans, block searches, and mining simulations only inspect chunks loaded when their
job runs; skipped chunks are reported. Block searches count every match but retain at most
`max_locate_results` (default 10,000), keeping the nearest matches. Reports use unique filenames
and never overwrite an existing report. Ore scan height bands are normalized and clamped to
the dimension; a band entirely outside its build height is rejected.

`drain` rechecks the fluid type before removing each scanned position, leaving replacement
fluids of a different type untouched.

`kill` processes at most 128 indexed entities per batch. It works from the console except for
`kill me`. Types are `all`, `animals`, `monsters`, `items`, `xp`, `players`, and `me`; only the last
two target players. Every enumerated type respects the protected entity-type tag. The explicit
`kill by <entity>` form bypasses that tag. Cancellation does not restore removed entities.

Recipe conflict scans compare ingredient assignments, horizontal mirrors, and shaped/shapeless
overlaps in bounded batches. Special recipes, non-simple custom ingredients, and recipes with
more than 81 ingredient slots are listed as skipped rather than treated as verified matches.
Shaped comparisons preserve empty-slot positions. Recipes whose displays fail or do not yield
one unambiguous static output are also skipped; outputs are captured once per scan.

Obtainability indexing processes recipes, loot nodes, trades, creative entries, and final item
classification in batches of at most 128 operations, encoding at most one loot table per job.
Unification audits process at most 32 tags per job. Reports identify unreadable sources;
registered trades are possible sources, not proof that their availability conditions can be met.
Creative-tab rebuilding and individual mod callbacks/codecs remain synchronous framework calls.

Loot simulations validate tables and referenced tables, item modifiers, and predicates before
rolling. Unsafe functions such as `exploration_map`, unverified custom behavior, recursive
references, and excessive per-roll workloads are rejected. Global loot modifiers are not run.
Reports record these limitations and the use of an independent random source; simulated rolls
do not advance world time or weather.

Spawn simulation uses each entry's declared inclusive pack-size range, subject to cluster
limits. Unloaded chunks and candidates without a loaded 32-block neighborhood are skipped.
Structure-specific spawn overrides, position-check/finalize-spawn events, and advancing world
conditions are not simulated; these limitations are recorded in reports.

Mining output retains item components, keeps distinct variants separate, and uses each
variant's actual maximum stack size when filling barrels. Chat shows the ten largest yields.
The simulation fails before placing barrels if it exceeds `max_mining_drop_variants` (default
10,000), preventing randomly generated components from consuming unlimited memory.

Structure loot tests resolve pools/templates, validate and roll loot, and place one chest/sign
pair per placement job. They attempt at most `max_structure_loot_chests` positions (default
256); occupied positions count toward that budget. Samples remain limited to 16 per table.
Resolution is capped at 16 times the chest budget (up to 4,096 pools/templates), the chest
budget's number of loot tables, and `max_drain_blocks` blocks per template. Unsupported loot
tables are skipped, and global loot modifiers are not evaluated. Partial placements are
recorded immediately, so `/mpa testStructureLoot clear` can remove them after cancellation or
failure. Cleanup also runs in cancellable batches; changed blocks other than chests/signs
are left alone.

`structureGrab` takes the two corners `/fill` takes, absolute or `~` relative, and writes the
region between them to `modpackassistant/structures/` in the game directory, named after the time
when no name is given. By default it writes the same structure twice under one base name: `<name>.nbt`,
the binary file that datapacks (`data/<namespace>/structure/`) and structure blocks load, and
`<name>.snbt`, the readable text form that NBT editors and VS Code plugins open. Pass `nbt` or `snbt`
to write only one. An existing file is never overwritten; a numbered suffix is added to the base name
instead, so the pair always stays together.

The grab reads the world the server is running, so chest, machine, and sign contents are complete.
Corners can be set one at a time while walking a build with `pos1` and `pos2`; each player keeps
their own selection, which resets when they change dimension and is dropped when they log out.
Regions are captured one chunk column per job, and chunks that are not loaded are loaded for that
job and released afterwards. Regions taller than the dimension are clamped to its build height and
the clamp is reported. `max_grab_blocks` (default 262,144) caps the region volume, since every
captured block is held in memory until the file is written.

Structure voids are always left out, so they read as untouched positions when the structure is
placed. `grab_ignore_air` additionally leaves out air, including cave and void air, so placing the
result does not clear what is already there. `grab_entities` saves the entities standing in the
region; players are never saved. Blocks are written in vanilla's order, full blocks first and block
entities last, so placement behaves the same as a structure saved by a structure block.

## Configuration

`config/modpackassistant-common.toml` holds the permission level for item inspection, the radius,
iteration, and block caps for each expensive command, the report and structure directories, the
structure grab defaults, and the job interval.

## Development verification

This branch targets Minecraft 26.1.2, NeoForge 26.1.2.73, and Java 25. Run `./gradlew build`,
`./gradlew runClientData` after message/tag changes, and `./gradlew runGameTestServer` for
regression tests. GameTests use the isolated `run-gametest-26.1.2` directory; add
`-x cleanGameTestWorld` to retain its existing test world.

## License

MIT, see [LICENSE.md](LICENSE.md).
