package com.breakinblocks.modpackassistant.gametest;

import com.breakinblocks.modpackassistant.analysis.BlockLocator;
import com.breakinblocks.modpackassistant.data.TestLootPlacements;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import com.breakinblocks.modpackassistant.grab.GrabFormat;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.showoff.ShowoffTemplateFiles;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class CommandGameTests {
    private CommandGameTests() {
    }

    private static final int RUN_WAIT = 40;

    private static CommandSourceStack sourceAt(GameTestHelper helper, BlockPos relative) {
        return CoreGameTests.source(CoreGameTests.fakePlayer(helper, relative));
    }

    private static void requireIdle(GameTestHelper helper) {
        helper.assertFalse(RunScheduler.isBusy(), "another run is active before the test started");
    }

    private static long countReports(ReportWriter.Family family) {
        Path directory = ReportWriter.directory(family);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        } catch (IOException e) {
            return -1;
        }
    }

    public static void clearRemovePredicateProtectsBedrock(GameTestHelper helper) {
        requireIdle(helper);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.BEDROCK);
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(4, 3, 2), Blocks.STONE);
        CommandSourceStack source = sourceAt(helper, new BlockPos(4, 5, 2));
        CoreGameTests.run(helper, source, "mpa clear 0 remove #minecraft:base_stone_overworld");
        helper.runAfterDelay(RUN_WAIT, () -> {
            helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(4, 1, 2));
            helper.assertBlockPresent(Blocks.IRON_ORE, new BlockPos(4, 2, 2));
            helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(4, 3, 2));
            CoreGameTests.run(helper, source, "mpa clear 0 remove minecraft:bedrock");
            helper.runAfterDelay(RUN_WAIT, () -> {
                helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(4, 1, 2));
                CoreGameTests.run(helper, source, "mpa clear 0 remove minecraft:bedrock false");
                helper.runAfterDelay(RUN_WAIT, () -> {
                    helper.assertBlockNotPresent(Blocks.BEDROCK, new BlockPos(4, 1, 2));
                    helper.succeed();
                });
            });
        });
    }

    public static void clearKeepOresAndModdedLeavesOres(GameTestHelper helper) {
        requireIdle(helper);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.DIRT);
        CommandSourceStack source = sourceAt(helper, new BlockPos(4, 4, 2));
        CoreGameTests.run(helper, source, "mpa clear 0 keep ores_and_modded");
        helper.runAfterDelay(RUN_WAIT, () -> {
            helper.assertBlockPresent(Blocks.IRON_ORE, new BlockPos(4, 1, 2));
            helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(4, 2, 2));
            helper.succeed();
        });
    }

    public static void drainRemovesConnectedFluidWithinRadius(GameTestHelper helper) {
        requireIdle(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.WATER);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.WATER);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.WATER);
        helper.setBlock(new BlockPos(8, 1, 1), Blocks.WATER);
        BlockPos start = helper.absolutePos(new BlockPos(1, 1, 1));
        CommandSourceStack source = sourceAt(helper, new BlockPos(8, 2, 8));
        CoreGameTests.run(helper, source, "mpa drain " + start.getX() + " " + start.getY() + " " + start.getZ() + " 2");
        helper.runAfterDelay(RUN_WAIT, () -> {
            helper.assertBlockNotPresent(Blocks.WATER, new BlockPos(1, 1, 1));
            helper.assertBlockNotPresent(Blocks.WATER, new BlockPos(2, 1, 1));
            helper.assertBlockNotPresent(Blocks.WATER, new BlockPos(3, 1, 1));
            helper.assertBlockPresent(Blocks.WATER, new BlockPos(8, 1, 1));
            helper.succeed();
        });
    }

    public static void killAllSkipsProtectedAndPlayers(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 4));
        Minecart cart = helper.spawn(EntityType.MINECART, new BlockPos(6, 1, 4));
        ServerPlayer player = CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8));
        CoreGameTests.run(helper, CoreGameTests.source(player), "mpa kill all");
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(zombie.isRemoved(), "zombie should be removed");
            helper.assertFalse(cart.isRemoved(), "minecart is tag protected and should survive");
            CoreGameTests.run(helper, CoreGameTests.source(player), "mpa kill by minecraft:minecart");
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(cart.isRemoved(), "kill by should bypass the protection tag");
                helper.succeed();
            });
        });
    }

    public static void tpdMovesVehicleWithPassenger(GameTestHelper helper) {
        Minecart cart = helper.spawn(EntityType.MINECART, new BlockPos(4, 1, 4));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 4));
        zombie.startRiding(cart, true, true);
        helper.assertTrue(zombie.isPassenger(), "zombie should be riding the minecart");
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "nether should exist");
        ServerPlayer player = CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8));
        BlockPos origin = cart.blockPosition();
        nether.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        CoreGameTests.run(helper, CoreGameTests.source(player), "mpa tpd minecraft:the_nether @e[type=minecraft:minecart,distance=..10]");
        helper.assertTrue(cart.isRemoved(), "original minecart should have been removed from the overworld");
        AABB column = new AABB(origin.getX() - 4, nether.getMinY(), origin.getZ() - 4, origin.getX() + 4, nether.getMaxY() + 1, origin.getZ() + 4);
        helper.succeedWhen(() -> {
            List<Minecart> carts = nether.getEntitiesOfClass(Minecart.class, column);
            helper.assertTrue(carts.size() == 1, "expected one minecart in the nether, found " + carts.size());
            List<Entity> passengers = carts.get(0).getPassengers();
            helper.assertTrue(passengers.size() == 1 && passengers.get(0) instanceof Zombie, "passenger should still be riding");
            carts.get(0).getPassengers().forEach(Entity::discard);
            carts.get(0).discard();
            nether.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
        });
    }

    public static void scanOresWritesReport(GameTestHelper helper) {
        requireIdle(helper);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.GOLD_ORE);
        long before = countReports(ReportWriter.Family.ORES);
        CommandSourceStack source = sourceAt(helper, new BlockPos(8, 1, 8));
        CoreGameTests.run(helper, source, "mpa scanOres 0 0 10");
        helper.runAfterDelay(RUN_WAIT, () -> {
            long after = countReports(ReportWriter.Family.ORES);
            helper.assertTrue(after >= before + 2, "expected two new ore reports, before " + before + " after " + after);
            helper.succeed();
        });
    }

    public static void locateBlockListsNearestFirstAndWritesReport(GameTestHelper helper) {
        requireIdle(helper);
        BlockPos near = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos far = helper.absolutePos(new BlockPos(1, 5, 1));
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.BUDDING_AMETHYST);
        helper.setBlock(new BlockPos(1, 5, 1), Blocks.BUDDING_AMETHYST);
        long before = countReports(ReportWriter.Family.BLOCKS);
        CommandSourceStack source = sourceAt(helper, new BlockPos(6, 1, 6));

        BlockLocator locator = new BlockLocator(Blocks.BUDDING_AMETHYST, source.getPosition());
        ChunkPos chunk = ChunkPos.containing(near);
        locator.scanChunk(helper.getLevel().getChunk(chunk.x(), chunk.z()), chunk);
        ChunkPos farChunk = ChunkPos.containing(far);
        if (!farChunk.equals(chunk)) {
            locator.scanChunk(helper.getLevel().getChunk(farChunk.x(), farChunk.z()), farChunk);
        }
        helper.assertTrue(locator.total() == 2, "expected the two placed blocks, found " + locator.total());
        helper.assertTrue(locator.nearest().get(0).pos().equals(near), "nearest hit should be " + near + " but was " + locator.nearest().get(0).pos());

        CoreGameTests.run(helper, source, "mpa locateBlock minecraft:budding_amethyst 0");
        helper.runAfterDelay(RUN_WAIT, () -> {
            long after = countReports(ReportWriter.Family.BLOCKS);
            helper.assertTrue(after >= before + 1, "expected a new block report, before " + before + " after " + after);
            helper.succeed();
        });
    }

    public static void radiusAboveLimitIsRefused(GameTestHelper helper) {
        requireIdle(helper);
        CommandSourceStack source = sourceAt(helper, new BlockPos(8, 1, 8));
        CoreGameTests.run(helper, source, "mpa clear 999 keep nothing");
        CoreGameTests.run(helper, source, "mpa scanOres 999");
        CoreGameTests.run(helper, source, "mpa minearea 999");
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(RunScheduler.isBusy(), "no run should have started for an out-of-range radius");
            helper.succeed();
        });
    }

    private static Path grabFile(String name, String extension) throws IOException {
        Path directory = GrabFiles.directory();
        if (!Files.isDirectory(directory)) {
            throw new IOException("structure directory " + directory + " was not created");
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> matches = files.filter(file -> file.getFileName().toString().startsWith(name)
                    && file.getFileName().toString().endsWith(extension)).toList();
            if (matches.size() != 1) {
                throw new IOException("expected one grabbed " + extension + " file named " + name + ", found " + matches.size());
            }
            return matches.get(0);
        }
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    public static void structureGrabCapturesBlockEntityContents(GameTestHelper helper) {
        requireIdle(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(2, 1, 1), ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        chest.setChanged();

        BlockPos from = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos to = helper.absolutePos(new BlockPos(3, 2, 3));
        String name = "gametest-" + System.nanoTime();
        CommandSourceStack source = sourceAt(helper, new BlockPos(4, 1, 4));
        CoreGameTests.run(helper, source, "mpa structureGrab "
                + from.getX() + " " + from.getY() + " " + from.getZ() + " "
                + to.getX() + " " + to.getY() + " " + to.getZ() + " " + name + " nbt");

        helper.runAfterDelay(RUN_WAIT, () -> {
            helper.assertFalse(RunScheduler.isBusy(), "the grab run should have finished");
            Path file;
            try {
                file = grabFile(name, GrabFormat.NBT_EXTENSION);
            } catch (IOException e) {
                helper.fail("locating the grabbed structure failed: " + e.getMessage());
                return;
            }
            try {
                CompoundTag structure = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

                ListTag size = structure.getListOrEmpty("size");
                helper.assertValueEqual(3, size.getIntOr(0, 0), "structure width");
                helper.assertValueEqual(2, size.getIntOr(1, 0), "structure height");
                helper.assertValueEqual(3, size.getIntOr(2, 0), "structure depth");

                ListTag blocks = structure.getListOrEmpty("blocks");
                helper.assertValueEqual(18, blocks.size(), "captured block count");
                helper.assertTrue(structure.getListOrEmpty("palette").size() >= 3, "palette should hold air, stone and the chest");
                helper.assertValueEqual(0, structure.getListOrEmpty("entities").size(), "entities are off by default");

                CompoundTag chestNbt = null;
                for (int index = 0; index < blocks.size(); index++) {
                    CompoundTag nbt = blocks.getCompoundOrEmpty(index).getCompoundOrEmpty("nbt");
                    if ("minecraft:chest".equals(nbt.getStringOr("id", ""))) {
                        chestNbt = nbt;
                    }
                }
                helper.assertTrue(chestNbt != null, "the chest block entity should have been saved");
                ListTag items = chestNbt.getListOrEmpty("Items");
                helper.assertValueEqual(1, items.size(), "saved chest item count");
                helper.assertValueEqual(7, items.getCompoundOrEmpty(0).getIntOr("count", 0), "saved chest stack size");

                String lastId = blocks.getCompoundOrEmpty(blocks.size() - 1).getCompoundOrEmpty("nbt").getStringOr("id", "");
                helper.assertTrue("minecraft:chest".equals(lastId), "block entities must be written last, but the last entry was " + lastId);
            } catch (IOException e) {
                helper.fail("reading the grabbed structure failed: " + e.getMessage());
                return;
            } finally {
                discard(file);
            }
            helper.succeed();
        });
    }

    public static void structureGrabSelectionWritesBothFormats(GameTestHelper helper) {
        requireIdle(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.GOLD_BLOCK);
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos second = helper.absolutePos(new BlockPos(2, 1, 2));
        String name = "gametest-" + System.nanoTime();
        CommandSourceStack source = sourceAt(helper, new BlockPos(4, 1, 4));

        CoreGameTests.run(helper, source, "mpa structuregrab pos1 " + first.getX() + " " + first.getY() + " " + first.getZ());
        CoreGameTests.run(helper, source, "mpa structuregrab pos2 " + second.getX() + " " + second.getY() + " " + second.getZ());
        CoreGameTests.run(helper, source, "mpa structuregrab grab " + name);

        helper.runAfterDelay(RUN_WAIT, () -> {
            helper.assertFalse(RunScheduler.isBusy(), "the grab run should have finished");
            Path snbtFile;
            Path nbtFile;
            try {
                snbtFile = grabFile(name, GrabFormat.SNBT_EXTENSION);
                nbtFile = grabFile(name, GrabFormat.NBT_EXTENSION);
            } catch (IOException e) {
                helper.fail("locating the grabbed structure failed: " + e.getMessage());
                return;
            }
            try {
                String base = name + GrabFormat.SNBT_EXTENSION;
                helper.assertTrue(snbtFile.getFileName().toString().equals(base),
                        "both files should share one base name, but the snbt was " + snbtFile.getFileName());
                String snbt = Files.readString(snbtFile);
                helper.assertTrue(snbt.contains("minecraft:gold_block"), "the selected gold block should appear in the snbt");
                helper.assertTrue(snbt.contains("size:"), "the snbt should carry a size list");

                CompoundTag structure = NbtIo.readCompressed(nbtFile, NbtAccounter.unlimitedHeap());
                helper.assertValueEqual(4, structure.getListOrEmpty("blocks").size(), "captured block count");
            } catch (IOException e) {
                helper.fail("reading the grabbed structure failed: " + e.getMessage());
                return;
            } finally {
                discard(snbtFile);
                discard(nbtFile);
            }
            CoreGameTests.run(helper, source, "mpa structuregrab clear");
            helper.succeed();
        });
    }

    public static void showoffRefusesPlayersWithoutTheClientMod(GameTestHelper helper) {
        requireIdle(helper);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        CommandSourceStack source = sourceAt(helper, new BlockPos(8, 1, 8));
        List<String> commands = List.of(
                "mpa showoff structure minecraft:igloo/top",
                "mpa showoff structure modpackassistant:missing",
                "mpa showoff file my_house",
                "mpa showoff file \"Castle Big.nbt\"",
                "mpa showoff entity minecraft:zombie {IsBaby:1b}",
                "mpa showoff entity minecraft:player",
                "mpa showoff angle 30 20",
                "mpa showoff zoom 2",
                "mpa showoff pan 0.1 -0.1",
                "mpa showoff reset",
                "mpa showoff background dark_aqua",
                "mpa showoff background hex 00FF00",
                "mpa showoff background transparent",
                "mpa showoff screenshot",
                "mpa showoff screenshot test 512 256",
                "mpa showoff close");
        for (String command : commands) {
            var parse = dispatcher.parse(command, source);
            helper.assertTrue(parse.getExceptions().isEmpty() && !parse.getReader().canRead(), "should parse: " + command);
            int result;
            try {
                result = dispatcher.execute(parse);
            } catch (CommandSyntaxException e) {
                throw new IllegalStateException(command + " threw " + e.getMessage(), e);
            }
            helper.assertTrue(result == 0, "should fail for a player without the client mod: " + command);
        }
        helper.succeed();
    }

    public static void showoffFileReadsGrabbedStructures(GameTestHelper helper) throws Exception {
        requireIdle(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_PLANKS);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)), new Vec3i(2, 1, 1), false, List.of());
        CompoundTag structure = template.save(new CompoundTag());

        Path directory = GrabFiles.directory().toAbsolutePath().normalize();
        Files.createDirectories(directory);
        String name = "showoff-gametest-" + System.nanoTime();
        Path nbt = directory.resolve(name + GrabFormat.NBT_EXTENSION);
        Path snbt = directory.resolve(name + GrabFormat.SNBT_EXTENSION);
        try {
            GrabFiles.writeNbt(nbt, structure);
            GrabFiles.writeSnbt(snbt, structure);
            helper.assertTrue(ShowoffTemplateFiles.list().contains(name), "the grab folder listing should include " + name);
            helper.assertTrue(nbt.equals(ShowoffTemplateFiles.resolve(name)), "a bare name should resolve to the .nbt file");
            helper.assertTrue(snbt.equals(ShowoffTemplateFiles.resolve(name + GrabFormat.SNBT_EXTENSION)), "an explicit .snbt name should resolve to it");
            helper.assertTrue(ShowoffTemplateFiles.resolve("../" + name) == null, "names must not leave the grab folder");
            helper.assertTrue(ShowoffTemplateFiles.resolve(name + "-missing") == null, "a missing file should not resolve");
            for (Path file : List.of(nbt, snbt)) {
                CompoundTag read = ShowoffTemplateFiles.read(file, helper.getLevel().getServer());
                helper.assertTrue(read.getListOrEmpty("blocks").size() == 2, file.getFileName() + " should hold two blocks");
            }
            helper.assertTrue(ShowoffTemplateFiles.id(nbt).getPath().equals(name), "the preview id should be the file's base name");
        } finally {
            discard(nbt);
            discard(snbt);
        }
        helper.succeed();
    }

    public static void aliasAndLowercaseLiteralsWork(GameTestHelper helper) {
        requireIdle(helper);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        CommandSourceStack source = sourceAt(helper, new BlockPos(8, 1, 8));
        helper.assertTrue(dispatcher.parse("modpackassistant cancel", source).getReader().canRead() == false, "full root should parse");
        helper.assertTrue(dispatcher.parse("mpa cancel", source).getReader().canRead() == false, "alias should parse");
        helper.assertTrue(dispatcher.parse("mpa scanores 0", source).getExceptions().isEmpty(), "lowercase literal should parse");
        helper.assertTrue(dispatcher.parse("mpa scanOres 0", source).getExceptions().isEmpty(), "camel case literal should parse");
        helper.succeed();
    }

    public static void structureLootPlacesAndClears(GameTestHelper helper) {
        TestLootPlacements record = TestLootPlacements.get(helper.getLevel().getServer());
        record.clear();
        ServerPlayer player = CoreGameTests.fakePlayer(helper, new BlockPos(2, 1, 12));
        CommandSourceStack source = CoreGameTests.source(player);
        CoreGameTests.run(helper, source, "mpa testStructureLoot minecraft:village_plains 1");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(RunScheduler.isBusy(), "waiting for structure loot placement"))
                .thenExecute(() -> {
                    helper.assertFalse(record.isEmpty(), "placements should be recorded");
                    int placed = record.positions().size();
                    helper.assertTrue(placed > 0 && placed % 2 == 0, "expected chest and sign pairs, got " + placed);
                    CoreGameTests.run(helper, source, "mpa testStructureLoot clear");
                })
                .thenWaitUntil(() -> helper.assertFalse(RunScheduler.isBusy(), "waiting for structure loot cleanup"))
                .thenExecute(() -> helper.assertTrue(record.isEmpty(), "record should be cleared"))
                .thenSucceed();
    }
}
