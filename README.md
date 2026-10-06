# Modpack Assistant

Server-side commands for modpack development and server administration on NeoForge 26.1.2.
World inspection, region editing, loot and spawn simulation, recipe and tag audits, and the usual
admin conveniences, plus a preview screen for showing off structures and entities. No blocks or
items. Vanilla clients work with every feature except the showoff preview, which needs the mod on the
client, and automatic clipboard copying, which falls back to click-to-copy chat.

Every command is available as `/modpackassistant ...` or `/mpa ...`. Camel-case names also accept
their lowercase spelling (`/mpa scanores` works the same as `/mpa scanOres`).

## Headless showoff API

Client integrations can call
`com.breakinblocks.modpackassistant.client.showoff.ShowoffClient.capture(...)`
without opening a preview screen:

```java
CompletableFuture<Path> image = ShowoffClient.capture(
        ShowoffSubject.ENTITY, Identifier.parse("minecraft:pig"), new CompoundTag(),
        ShowoffView.DEFAULT, ShowoffBackground.TRANSPARENT, 1024, 1024, output);
```

The client must have a loaded world and an active render loop. Calls from other threads are
scheduled on the client thread; do not block that thread waiting for the future. Create the
parent directory before calling. The future completes after the full-size PNG is written to
the supplied path, or exceptionally on scene, measurement, rendering, readback, or write failure.
Invalid arguments throw immediately. `STRUCTURE` subjects accept the same structure-template NBT
as the preview. Rendering uses the existing scene measurements and framebuffer capture pipeline;
orchestration and image post-processing belong to the caller.

For mannequin captures, supply vanilla `equipment` NBT and an optional `Pose` compound:

```snbt
{
  profile: {name: "TheonlyTazz"},
  equipment: {
    head: {id: "minecraft:diamond_helmet", count: 1},
    chest: {id: "minecraft:diamond_chestplate", count: 1},
    mainhand: {id: "minecraft:diamond_sword", count: 1},
    offhand: {id: "minecraft:shield", count: 1}
  },
  Pose: {
    Head: [0.0f, 15.0f, 0.0f],
    RightArm: [-45.0f, 0.0f, 0.0f],
    LeftArm: [-20.0f, 0.0f, 0.0f]
  }
}
```

Pass this compound as `data` with entity ID `minecraft:mannequin`. Equipment keys are `head`,
`chest`, `legs`, `feet`, `mainhand`, and `offhand`. Pose keys are `Head`, `Body`, `LeftArm`,
`RightArm`, `LeftLeg`, and `RightLeg`; each contains pitch, yaw, and roll in degrees, from -180
to 180. Omitted limbs use zero rotation. Armor and held items follow the specified pose.
Malformed equipment or pose data completes the capture future exceptionally.
When `profile` is supplied, capture waits for profile resolution and skin download before measuring
or rendering. Failed or timed-out skin requests fail the capture instead of producing a default-skin
image. Omitting `profile` uses the mannequin's default skin.

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

## Showoff preview

Showoff draws a structure or an entity on its own, in an isometric view, and saves clean PNG
screenshots of it. It is meant for quest book images, mod and pack pages, wiki pictures, and checking
what a template or a grabbed build looks like without placing it. Commands control the subject,
view, background and screenshots, so a script or an MCP server can produce images with no one at
the game. The player skin and limb controls are available in the preview screen.

### Requirements

- Modpack Assistant on the server and on the client of the player who will see the preview. The
  server sends the subject to that player's client, and the client does all the drawing and writes
  the screenshots. For a player without the mod on their client, every showoff command fails with a
  message saying so.
- Permission level 2, the same as the other world commands. In singleplayer that means cheats on.
- The player has to be in a world. The preview does not pause a singleplayer game.

### Quick start

1. Run `/mpa showoff structure minecraft:village/plains/houses/plains_small_house_1`. Tab completion
   lists every template the server knows about.
2. The preview opens: a large panel over a black backdrop with the house in the middle.
3. Drag with the left mouse button to turn it, drag with the right button to move it, and scroll to
   zoom in and out.
4. Click a colour in the side bar to change the background, or Transparent for a see-through PNG.
5. Click Screenshot. Chat shows where the PNG was saved; click the path to open it.
6. Click Done or press Escape to close the preview.

### Opening something to show

Opening a new subject replaces whatever the preview was showing. Zoom and pan go back to their
defaults. The background colour and the yaw and pitch stay as they were last set, until the game is
closed, so a run of screenshots can share one angle without setting it each time.

#### Structure templates

```
/mpa showoff structure <template>
```

Takes the same ids as `/place template`: templates from vanilla, mods and datapacks
(`data/<namespace>/structure/`), and structures saved in the world with a structure block. For example
`minecraft:igloo/top`, `minecraft:shipwreck/with_mast` or `minecraft:ancient_city/city_center/city_center_1`.

#### Structure files

```
/mpa showoff file <name>
```

Reads a structure file straight from the folder `structureGrab` writes to, `modpackassistant/structures/`
in the game directory unless `structure_directory` in the config points somewhere else. This is the way
to look at a grab without copying it into a datapack first, and any other `.nbt` or `.snbt` structure
file dropped into that folder works as well, including files in subfolders.

- The name is the path inside that folder, with or without the extension: `my_house`,
  `my_house.snbt` and `builds/tower` all work.
- Without an extension, `.nbt` is used when both an `.nbt` and an `.snbt` exist, as they do after a
  default grab.
- Names containing spaces or other characters outside letters, digits, `_`, `-`, `.` and `+` need
  quotes: `/mpa showoff file "Castle Big.nbt"`. Tab completion lists what is in the folder and adds the
  quotes where they are needed.
- A name cannot reach outside the folder; `../` paths are treated as not found.
- Files saved by older game versions are updated on load the same way the game updates structure
  files it loads itself.

#### Entities

```
/mpa showoff entity <entity> [nbt]
```

Any entity `/summon` accepts, with the same optional NBT. The entity is built on the client for the
preview only and is never added to a world, so nothing spawns and no spawn randomisation runs. Without
NBT an entity shows its default variant, colour and equipment (none).

```
/mpa showoff entity minecraft:ender_dragon
/mpa showoff entity minecraft:sheep {Color:14}
/mpa showoff entity minecraft:zombie {IsBaby:1b}
/mpa showoff entity minecraft:painting {variant:"minecraft:kebab"}
/mpa showoff entity minecraft:block_display {block_state:{Name:"minecraft:diamond_block"}}
/mpa showoff entity minecraft:item_display {item:{id:"minecraft:diamond_sword",count:1}}
/mpa showoff entity minecraft:spider {Passengers:[{id:"minecraft:skeleton"}]}
```

Riders given with `Passengers` are drawn in their seats.

#### Player showoff

Run `/mpa showoff player <playerName|UUID>` to open the player editor with that player's skin.
For example, `/mpa showoff player Dinnerbone`; tab completion suggests online player names.
You can also run `/mpa showoff entity minecraft:mannequin` and enter a username,
a dashed UUID or a compact UUID beside the entity title. The client resolves the profile and
downloads its skin asynchronously after a short typing delay; Enter submits immediately. A default
skin appears while loading. The status reports lookup failures, and hovering it shows the full message.

The Limbs panel sits below the background selector. Click the part selector to cycle through Head,
Body, Left Arm, Right Arm, Left Leg and Right Leg. Set Pitch, Yaw and Roll independently from
-180 to 180 degrees; scrolling over a slider changes its angle by one degree. Each part retains its
angles when another part is selected. Reset view also clears the limb pose for player previews.
Screenshots use the selected skin and pose, including the skin's outer layers and slim arms.

Click the chestplate button at the top left of the preview to open the equipment slots. Select a
slot to browse items, and use the search field to filter by item name or registry ID (including the
mod namespace). Armor slots show matching equipment; either hand accepts all items. Select the clear
entry or right-click an equipment slot to empty it. Equipment appears immediately and follows the
selected limb pose. Escape closes the item picker, then the equipment popup.

The standalone mannequin editor uses its own client mannequin; passengers and NBT poses are not
included. Mannequins inside structure previews keep their normal entity rendering.

### The preview screen

The title bar names the subject. The preview area takes up most of the panel; the side bar on the right
holds the background colours and three buttons, and the line under the preview shows the current yaw,
pitch and zoom (plus a reminder of the mouse controls when there is room for it).

| Input | Effect |
| --- | --- |
| Left drag in the preview | Rotate. Left and right turn the subject around; up and down tilt the camera |
| Right or middle drag in the preview | Pan the subject around the frame |
| Scroll in the preview | Zoom in or out |
| Yaw and Pitch sliders | Set either angle on its own, in whole degrees; scroll over a slider to step it by one degree |
| A colour swatch | Set that background colour; hover a swatch to see its name |
| Transparent | Transparent background, shown as a checkerboard on screen |
| Reset view | Default angle, zoom 1, no pan (keeps the background); later previews open at the default angle again |
| Screenshot | Save the preview area as a PNG with a generated name |
| Done or Escape | Close the preview |

### Controlling the view with commands

The same controls as commands, for exact values and for automation. They act on the preview that is
open for the player running them.

| Command | Range | Default | Effect |
| --- | --- | --- | --- |
| `/mpa showoff angle <yaw> <pitch>` | yaw any, pitch -90 to 90 | 45, 35.264 | Set the view angle in degrees |
| `/mpa showoff zoom <zoom>` | 0.1 to 20 | 1 | 1 fits the subject to the preview, 2 is twice as big, 0.5 half |
| `/mpa showoff pan <x> <y>` | -2 to 2 each | 0, 0 | Shift the subject by a fraction of the preview's width and height; positive x is right, positive y is up |
| `/mpa showoff reset` | | | Default angle, zoom 1, no pan |
| `/mpa showoff background ...` | | black | See [Backgrounds](#backgrounds) |
| `/mpa showoff screenshot ...` | | | See [Screenshots](#screenshots) |
| `/mpa showoff close` | | | Close the preview |

`angle`, `zoom` and `pan` each replace only their own values, so `zoom` after `angle` keeps the
angle. Yaw values outside -180 to 180 are wrapped, so `angle 200 15` becomes yaw -160.

Yaw picks the side the camera looks from, turning around the subject's vertical axis. Pitch is how
high the camera sits: 0 looks straight on, 90 looks straight down and negative values look up from
below. Entities are set up facing south, so yaw 0 shows an entity's front. For structures, south is
the side facing positive Z in the template, which is south when the template is placed without
rotation.

| View | Command |
| --- | --- |
| Default isometric, from the south-west | `angle 45 35.264` |
| Isometric from the south-east | `angle -45 35.264` |
| Isometric from the north-west, the back corner | `angle 135 35.264` |
| Front (south) | `angle 0 0` |
| West side | `angle 90 0` |
| East side | `angle -90 0` |
| Back (north) | `angle 180 0` |
| Top down | `angle 0 90` |
| From below | `angle 45 -30` |

The default pitch of 35.264 degrees is true isometric, where all three axes are drawn the same length.
Pitch 30 gives the slightly flatter look of block icons in inventories.

Zoom 1 frames what is actually drawn, not the hitbox or the template's box. When a subject opens it is
drawn once from the front and once from above, and the drawn area is measured. That keeps models much
larger than their hitbox inside the frame, such as the ender dragon, phantoms, ghasts and boats, and
leaves out empty space inside a template. The fit follows the angle, so turning the subject can change
its size in the frame a little.

### Backgrounds

```
/mpa showoff background <colour>
/mpa showoff background hex <rrggbb>
/mpa showoff background transparent
```

The named colours are the 16 chat colours in the side bar: `black`, `dark_blue`, `dark_green`,
`dark_aqua`, `dark_red`, `dark_purple`, `gold`, `gray`, `dark_gray`, `blue`, `green`, `aqua`, `red`,
`light_purple`, `yellow` and `white`. `hex` takes any colour as six hex digits without a `#`, such as
`hex 00FF00` for a pure green screen, or three digits as shorthand (`hex 0F0`). `transparent` keeps
alpha in the screenshot, so the PNG can go straight onto another background; translucent blocks such as
glass and water keep their partial transparency.

The background carries over to the next subject opened, so a batch of screenshots only needs to set it
once. It resets to black when the game restarts.

### Screenshots

```
/mpa showoff screenshot [name] [width height]
```

A screenshot contains only the preview, never the rest of the game window, and is drawn fresh at the
requested size rather than scaled up from the screen, so large images stay sharp.

- Files go to `modpackassistant/showoff/` in the game directory of the client that has the preview
  open (`showoff_directory` in the config).
- With a name, the file is `<name>.png`, and an existing file of that name is replaced, so a script
  always knows where its image is. Names are lowercased, and characters other than letters, digits,
  `.`, `_` and `-` become `_`.
- Without a name, as with the Screenshot button, the file is named after the subject and the time in
  UTC, for example `plains_small_house_1-20260927-191459.png`. These never replace an existing file;
  `_2`, `_3` and so on are added instead.
- Width and height are in pixels, from 16 to 8,192, and are given together. Without them the image is
  the size the preview has on screen, which depends on the window size and GUI scale. Graphics cards
  set their own texture limit, and a size above it is refused with a message giving the limit.
- The screenshot uses the view as it is when the command arrives. Changing the view straight after
  does not affect it.
- The PNG is written to a temporary file and then renamed, so a file at the final path is always
  complete. Chat shows the saved path, and clicking it opens the file. The client log also records the
  full path in a line starting `Saved showoff screenshot`.

### Automation with scripts and MCP servers

Every showoff command works from the server console and over RCON as well as from chat, which is what
lets a script or an MCP server produce images unattended. A player still has to be in the game with
the mod on their client, because that client does the drawing, but nobody needs to touch it.

The commands act on the player who runs them, so from the console or RCON wrap each one in
`/execute as <player> run`:

```
execute as Dev run mpa showoff entity minecraft:ender_dragon
execute as Dev run mpa showoff background transparent
execute as Dev run mpa showoff angle -60 25
execute as Dev run mpa showoff zoom 1.1
execute as Dev run mpa showoff screenshot dragon 1024 1024
execute as Dev run mpa showoff close
```

A typical setup is a dedicated server (the `runServer` dev server works) with RCON turned on, and a
client with the mod joined to it and left running. RCON is set in the server's `server.properties`:

```
enable-rcon=true
rcon.port=25575
rcon.password=<a long random password>
```

RCON listens on every network interface, so use a strong password and keep the port closed to anything
outside the machine or local network. Any RCON client can send the commands; the MCP server just needs
to open a connection, log in with the password and send each command as a line. Singleplayer worlds
have no RCON, so automation needs a server.

What comes back, and where:

- The RCON reply to each command is the server's message: `Showing structure ... (7 by 7 by 7, 343
  blocks, 0 entities)`, `Showoff angle set to yaw -60.00, pitch 25.00`, `Requested showoff screenshot
  dragon.png`, and so on. Problems the server can check are refused here with a reason: a player without
  the mod on their client, an unknown template, a missing file, a template over the size cap, an entity
  that cannot be summoned, or an argument out of range.
- Everything after that happens on the client, which cannot reply through RCON. The saved screenshot,
  a failed save and "No showoff view is open" appear in that player's chat and client log instead.
- The screenshot itself is the reliable result. With a named screenshot, the script knows the path in
  advance: `<client game directory>/modpackassistant/showoff/<name>.png`. Watch for that file; because
  it is renamed into place when finished, it is safe to read as soon as it exists. Delete or rename the
  old file first if the script needs to tell a new image from an old one of the same name.

Timing and order:

- Commands are handled in the order they are sent, so open, adjust and capture can be sent back to back
  with no waiting in between.
- A screenshot requested straight after `structure`, `file` or `entity` waits for that subject's
  framing to be measured, a frame or two, so it comes out framed correctly.
- A screenshot usually appears within a second. Large templates take longer to open, since the client
  builds the whole structure first.
- The client can be behind other windows while this runs; it does not need focus.
- The preview has to be open for `angle`, `zoom`, `pan`, `background`, `screenshot` and `close` to do
  anything. If someone closes it, the next of those commands reports "No showoff view is open" in chat
  while the RCON reply still says the command was sent, so a script should open the subject again at
  the start of each image rather than rely on an earlier preview.
- A newly opened preview keeps the yaw and pitch of the last one, whether they were set by a command,
  a slider or a drag. A script that needs a known angle should send `angle` or `reset` after opening.

A batch in a shell script, using the `mcrcon` client as an example:

```
for mob in zombie skeleton creeper spider enderman; do
  mcrcon -H localhost -P 25575 -p "$RCON_PASSWORD" \
    "execute as Dev run mpa showoff entity minecraft:$mob" \
    "execute as Dev run mpa showoff background transparent" \
    "execute as Dev run mpa showoff screenshot $mob 512 512"
done
```

### Worked examples

Quest book image of a mob on a transparent background:

```
/mpa showoff entity minecraft:warden
/mpa showoff background transparent
/mpa showoff screenshot warden 512 512
```

A build from your world, from grab to picture:

```
/mpa structureGrab pos1
/mpa structureGrab pos2
/mpa structureGrab grab my_base
/mpa showoff file my_base
/mpa showoff screenshot my_base 1920 1080
```

Walk to one corner of the build for `pos1` and the opposite corner for `pos2` (or give coordinates).
The grab runs as a job, so wait for its "Structure written to" message before opening the file. If a
grab named `my_base` already exists, the new one is saved as `my_base_1`, which that message shows.

Green screen for chroma keying in an image editor:

```
/mpa showoff structure minecraft:igloo/top
/mpa showoff background hex 00FF00
/mpa showoff screenshot igloo_green 1024 1024
```

A straight side-on view, filling the frame:

```
/mpa showoff structure minecraft:shipwreck/with_mast
/mpa showoff angle 90 0
/mpa showoff zoom 1.05
/mpa showoff screenshot shipwreck_side 1600 900
```

### What is drawn

- Blocks with their normal models, smooth lighting and biome tinting. Grass, leaves and water are
  tinted as in a plains biome.
- Water and lava, with translucent water.
- Block entities such as chests, bells, banners, signs, beds, skulls, spawners with their mob, and
  campfires with their food.
- Entities saved in a template, where they were saved, and riders in their seats.
- Structure voids and structure blocks are hidden, and jigsaw blocks show the block they turn into
  after generation, so templates look the way they do in the world.

Everything is lit at full brightness with no world light, sky or weather, and shown as a still image:
entities stand in a neutral pose, and moving parts of block entities are frozen. Name tags, shadows and
leads are left out. Nothing about the world the player is standing in affects the picture.

A modded block entity or entity whose renderer needs a real world around it can fail to draw. The rest
of the preview still draws; the one that failed is left out and a warning naming it goes to the client
log.

### Troubleshooting

| Message | Meaning |
| --- | --- |
| `... does not have Modpack Assistant on their client, which the showoff view needs` | Install the mod on that player's client, or run the command as a player who has it |
| `This command can only be run in-game as a player` | Run from the console or RCON without `execute as <player> run` |
| `Unknown structure template ...` | The id is not a template the server knows; use tab completion or check `/place template` |
| `No structure file named ... in ...` | Nothing by that name in the structure folder; the name is relative to that folder |
| `Could not read structure file ...` | The file is damaged or is not a structure file; the server log has the details |
| `Structure template ... holds ... blocks, above the configured maximum` | Raise `max_showoff_blocks` in the config |
| `Entity ... cannot be summoned, so it cannot be shown` | Players and a few special entities cannot be created on their own |
| `No showoff view is open` (in client chat) | The preview was closed; open a subject first |
| `A ... by ... screenshot is above this GPU's texture limit of ...` | Ask for a smaller size |
| No screenshot file appeared | Look in the client's chat and log rather than the server's; the server only confirms the request |

`max_showoff_blocks` (default 262,144) limits how many blocks a template sent to a client may hold,
since the whole template travels to the client and is kept in memory while it is shown.

## Configuration

`config/modpackassistant-common.toml` holds the permission level for item inspection, the radius,
iteration, and block caps for each expensive command, the report, structure and showoff screenshot
directories, the structure grab defaults, the showoff template cap, and the job interval.

## Development verification

This branch targets Minecraft 26.1.2, NeoForge 26.1.2.73, and Java 25. Run `./gradlew build`,
`./gradlew runClientData` after message/tag changes, and `./gradlew runGameTestServer` for
regression tests. GameTests use the isolated `run-gametest-26.1.2` directory; add
`-x cleanGameTestWorld` to retain its existing test world.

## License

MIT, see [LICENSE.md](LICENSE.md).
