package com.breakinblocks.modpackassistant.gametest;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.analysis.BlockLocator;
import com.breakinblocks.modpackassistant.analysis.DropAccumulator;
import com.breakinblocks.modpackassistant.analysis.LootSafety;
import com.breakinblocks.modpackassistant.analysis.RecipeConflictFinder;
import com.breakinblocks.modpackassistant.analysis.SpawnSimulator;
import com.breakinblocks.modpackassistant.commands.admin.TpdCommand;
import com.breakinblocks.modpackassistant.jobs.ChunkAccessor;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.net.MANetworking;
import com.breakinblocks.modpackassistant.net.SetClipboardPayload;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.RootCommandNode;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.phys.Vec3;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public final class RegressionGameTests {

    public static void registryAuditsResumeAtTheirBudgets(GameTestHelper helper) {
        var index = new com.breakinblocks.modpackassistant.analysis.ObtainabilityIndex(helper.getLevel());
        helper.assertFalse(index.step("minecraft", 1), "one operation must not complete the obtainability index");
        int batches = 0;
        while (!index.step("minecraft", 128)) {
            if (++batches > 100000) helper.fail("obtainability cursor did not terminate");
        }
        helper.assertTrue(batches > 10, "registry work must be split across batches");
        var auditor = new com.breakinblocks.modpackassistant.analysis.UnificationAuditor(null);
        var job = auditor.auditJob(BuiltInRegistries.ITEM, 1);
        helper.assertFalse(job.getAsBoolean(), "one tag must not complete the unification audit");
        batches = 0;
        while (!job.getAsBoolean()) {
            if (++batches > 100000) helper.fail("tag cursor did not terminate");
        }
        helper.assertTrue(auditor.resolvedCount() + auditor.unresolvedCount() > 0, "material tags should still be classified");
        helper.succeed();
    }

    public static void unreadableRecipeDisplaysAreSkipped(GameTestHelper helper) {
        var finder = new RecipeConflictFinder(helper.getLevel());
        for (int i = 0; i < 2; i++) {
            var recipe = new ShapelessRecipe(new Recipe.CommonInfo(true),
                    new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                    new ItemStackTemplate(Items.DIAMOND), List.of(Ingredient.of(Items.STONE))) {
                @Override
                public List<net.minecraft.world.item.crafting.display.RecipeDisplay> display() {
                    throw new IllegalStateException("dynamic display");
                }
            };
            finder.addRecipe(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id("unreadable_" + i)), recipe), null);
        }
        helper.assertTrue(finder.skipped().size() == 2 && finder.buckets().isEmpty(), "unreadable results must be skipped, not declared duplicates");
        helper.succeed();
    }

    public static void structurePlacementAndCleanupRetainPartialRecords(GameTestHelper helper) {
        helper.assertFalse(RunScheduler.isBusy(), "scheduler should be idle");
        var record = com.breakinblocks.modpackassistant.data.TestLootPlacements.get(helper.getLevel().getServer());
        helper.assertTrue(record.isEmpty(), "no previous test placements should remain");
        var source = CoreGameTests.source(CoreGameTests.fakePlayer(helper, new BlockPos(2, 1, 12)));
        AtomicInteger before = new AtomicInteger();
        CoreGameTests.run(helper, source, "mpa testStructureLoot minecraft:village_plains 16");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(record.positions().size() >= 4, "waiting for two chest/sign pairs"))
                .thenExecute(() -> {
                    helper.assertTrue(RunScheduler.cancel(source), "placement should be cancellable");
                    before.set(record.positions().size());
                    CoreGameTests.run(helper, source, "mpa testStructureLoot clear");
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    helper.assertTrue(RunScheduler.cancel(source), "cleanup should be cancellable");
                    helper.assertTrue(!record.isEmpty() && record.positions().size() < before.get(), "remaining cleanup records must survive cancellation");
                    CoreGameTests.run(helper, source, "mpa testStructureLoot clear");
                })
                .thenWaitUntil(() -> helper.assertFalse(RunScheduler.isBusy(), "waiting for resumed cleanup"))
                .thenExecute(() -> helper.assertTrue(record.isEmpty(), "resumed cleanup should consume all records"))
                .thenSucceed();
    }

    private static RecipeHolder<?> shapeless(String id, Item result, Ingredient... inputs) {
        return new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id(id)), new ShapelessRecipe(new Recipe.CommonInfo(true),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""), new ItemStackTemplate(result), List.of(inputs)));
    }

    private static long conflicts(GameTestHelper helper, RecipeHolder<?>... recipes) {
        RecipeConflictFinder finder = new RecipeConflictFinder(helper.getLevel());
        finder.prepare(List.of(recipes), null);
        finder.buckets().forEach(finder::process);
        return finder.conflictCount();
    }

    public static void recipeMatchingIsOrderIndependentAndMirrored(GameTestHelper helper) {
        Ingredient stone = Ingredient.of(Items.STONE);
        Ingredient dirt = Ingredient.of(Items.DIRT);
        var broad = shapeless("broad", Items.DIAMOND, Ingredient.of(Items.STONE, Items.DIRT), stone);
        var ordered = shapeless("ordered", Items.EMERALD, stone, dirt);
        var reversed = shapeless("reversed", Items.EMERALD, dirt, stone);
        helper.assertTrue(conflicts(helper, broad, ordered) == 1, "flexible ingredient must be reassigned");
        helper.assertTrue(conflicts(helper, broad, reversed) == 1, "ingredient order must not affect conflicts");
        Map<Character, Ingredient> keys = Map.of('S', stone, 'D', dirt);
        var shaped = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id("shaped")), new ShapedRecipe(new Recipe.CommonInfo(true), new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                ShapedRecipePattern.of(keys, "SD"), new ItemStackTemplate(Items.DIAMOND)));
        var mirror = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id("mirror")), new ShapedRecipe(new Recipe.CommonInfo(true), new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                ShapedRecipePattern.of(keys, "DS"), new ItemStackTemplate(Items.EMERALD)));
        helper.assertTrue(conflicts(helper, shaped, mirror) == 1, "mirrored shaped recipes must conflict");
        helper.assertTrue(conflicts(helper, shaped, ordered) == 1, "shaped and shapeless recipes must be compared");
        var withHole = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id("hole")), new ShapedRecipe(new Recipe.CommonInfo(true), new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                ShapedRecipePattern.of(keys, "S D"), new ItemStackTemplate(Items.DIAMOND)));
        helper.assertTrue(conflicts(helper, withHole, ordered) == 1, "empty shaped slots must not count as ingredients");
        helper.succeed();
    }

    public static void shapedRecipeHolesRetainTheirLayout(GameTestHelper helper) {
        var keys = Map.of('S', Ingredient.of(Items.STONE), 'D', Ingredient.of(Items.DIRT));
        var common = new Recipe.CommonInfo(true);
        var book = new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, "");
        var left = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id("hole_left")),
                new ShapedRecipe(common, book, ShapedRecipePattern.of(keys, "S D", "S  "), new ItemStackTemplate(Items.DIAMOND)));
        var right = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, ModpackAssistant.id("hole_right")),
                new ShapedRecipe(common, book, ShapedRecipePattern.of(keys, "SD ", "  S"), new ItemStackTemplate(Items.EMERALD)));
        helper.assertTrue(conflicts(helper, left, right) == 0, "equal compact ingredients must not erase shaped slot positions");
        helper.succeed();
    }

    public static void oreScanRejectsOutOfDimensionBands(GameTestHelper helper) throws Exception {
        helper.assertFalse(RunScheduler.isBusy(), "scheduler should be idle");
        var source = CoreGameTests.source(CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8)));
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        helper.assertTrue(dispatcher.execute("mpa scanOres 0 100000 100001", source) == 0, "band above dimension must fail");
        helper.assertTrue(dispatcher.execute("mpa scanOres 0 -100001 -100000", source) == 0, "band below dimension must fail");
        helper.assertFalse(RunScheduler.isBusy(), "invalid height bands must not start runs");
        helper.succeed();
    }

    public static void drainRechecksFluidBeforeRemoval(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
        Class<?> type = Class.forName("com.breakinblocks.modpackassistant.commands.world.DrainCommand$DrainRun");
        var constructor = type.getDeclaredConstructor(net.minecraft.server.level.ServerLevel.class, BlockPos.class,
                net.minecraft.world.level.material.Fluid.class, int.class, int.class);
        constructor.setAccessible(true);
        Object drain = constructor.newInstance(level, pos, net.minecraft.world.level.material.Fluids.WATER, 1, 1);
        var remove = type.getDeclaredMethod("remove", int.class, int.class);
        remove.setAccessible(true);
        level.setBlock(pos, Blocks.LAVA.defaultBlockState(), 2);
        remove.invoke(drain, 0, 1);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.LAVA), "replacement lava must not be drained by a water run");
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
        remove.invoke(drain, 0, 1);
        helper.assertTrue(level.getBlockState(pos).isAir(), "matching water must still be removed");
        helper.succeed();
    }

    public static void recipeComparisonResumesAtItsBudget(GameTestHelper helper) {
        RecipeConflictFinder finder = new RecipeConflictFinder(helper.getLevel());
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (int i = 0; i < 100; i++) recipes.add(shapeless("budget_" + i, Items.DIAMOND, Ingredient.of(Items.STONE)));
        finder.prepare(recipes, null);
        var bucket = finder.buckets().getFirst();
        helper.assertFalse(finder.processBatch(bucket, 1), "one operation must not process a whole bucket");
        int batches = 1;
        while (!finder.processBatch(bucket, 16)) {
            if (++batches > 1000) helper.fail("recipe cursor did not terminate");
        }
        helper.assertTrue(batches > 100, "pair comparisons must be distributed across batches");
        helper.assertTrue(finder.duplicateCount() == 1, "resumed grouping should preserve all duplicates");
        helper.succeed();
    }

    public static void vanillaClientsRetainMessagesArgumentsAndAliases(GameTestHelper helper) throws Exception {
        helper.assertFalse(RunScheduler.isBusy(), "scheduler should be idle");
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        CommandSourceStack source = CoreGameTests.source(CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8)));
        for (String root : List.of("mpa", "modpackassistant")) {
            for (String name : List.of("findconflicts", "finduncraftables", "auditunification")) {
                helper.assertTrue(dispatcher.execute(root + " " + name, source) > 0, "argument-free alias should execute: " + name);
                helper.assertTrue(RunScheduler.cancel(source), "alias should have started a cancellable run");
            }
        }
        var cleaner = Class.forName("net.neoforged.neoforge.network.filters.CommandTreeCleaner")
                .getDeclaredMethod("cleanArgumentTypes", RootCommandNode.class, Predicate.class);
        cleaner.setAccessible(true);
        Predicate<ArgumentType<?>> vanilla = argument -> {
            var key = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(ArgumentTypeInfos.byClass(argument));
            return key != null && (key.getNamespace().equals("minecraft") || key.getNamespace().equals("brigadier"));
        };
        @SuppressWarnings("unchecked")
        RootCommandNode<CommandSourceStack> filtered = (RootCommandNode<CommandSourceStack>) cleaner.invoke(null, dispatcher.getRoot(), vanilla);
        var root = filtered.getChild("modpackassistant");
        helper.assertTrue(root.getChild("kill").getChild("type") != null, "kill argument should survive vanilla filtering");
        helper.assertTrue(root.getChild("exportTags").getChild("registry").getChild("format") != null, "export arguments should survive");
        helper.assertTrue(root.getChild("clear").getChild("radius").getChild("keep").getChild("keep") != null, "keep argument should survive");
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("mpa kill ", source)).join().getList();
        helper.assertTrue(suggestions.stream().anyMatch(suggestion -> suggestion.getText().equals("monsters")), "enum suggestions should remain available");
        var contents = (TranslatableContents) Messages.HEAL_DONE.get("Example").getContents();
        helper.assertTrue(contents.getFallback() != null && Messages.HEAL_DONE.get("Example").getString().contains("Example"), "vanilla fallback must include arguments");
        helper.succeed();
    }

    public static void AnalysisSkipsUnloadedChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        ChunkPos absent = new ChunkPos(123456, 123456);
        helper.assertTrue(level.getChunkSource().getChunkNow(absent.x(), absent.z()) == null, "test chunk must be absent");
        AtomicInteger visits = new AtomicInteger();
        helper.assertFalse(ChunkAccessor.withLoadedChunk(level, absent, chunk -> visits.incrementAndGet()), "unloaded chunk should be skipped");
        var biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        SpawnSimulator simulator = new SpawnSimulator(level, biome, Collections.nCopies(289, absent), Vec3.ZERO, 1);
        simulator.simulateBatch();
        helper.assertTrue(visits.get() == 0 && simulator.totalIndividuals() == 0, "unloaded positions must not be evaluated");
        helper.assertTrue(level.getChunkSource().getChunkNow(absent.x(), absent.z()) == null, "analysis must not load or generate terrain");
        helper.succeed();
    }

    public static void blockSearchRetainsOnlyNearestHits(GameTestHelper helper) {
        List<BlockPos> positions = List.of(new BlockPos(3, 2, 3), new BlockPos(4, 2, 3), new BlockPos(5, 2, 3));
        for (BlockPos pos : positions) helper.setBlock(pos, Blocks.NETHERITE_BLOCK);
        BlockPos origin = helper.absolutePos(positions.getFirst());
        BlockLocator locator = new BlockLocator(Blocks.NETHERITE_BLOCK, Vec3.atCenterOf(origin), 2);
        var chunks = new HashSet<ChunkPos>();
        for (BlockPos pos : positions) chunks.add(ChunkPos.containing(helper.absolutePos(pos)));
        for (ChunkPos pos : chunks) locator.scanChunk(helper.getLevel().getChunk(pos.x(), pos.z()), pos);
        helper.assertTrue(locator.total() >= 3 && locator.retained() == 2, "all hits should be counted but only the budget retained");
        helper.assertTrue(locator.nearest().getFirst().pos().equals(origin), "nearest hit must survive trimming");
        helper.succeed();
    }

    public static void unsafeLootIsRejectedThroughReferences(GameTestHelper helper) {
        var level = helper.getLevel();
        var unsafe = ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/shipwreck_map"));
        for (LootTable table : List.of(level.getServer().reloadableRegistries().getLootTable(unsafe),
                LootTable.lootTable().withPool(LootPool.lootPool().add(NestedLootTable.lootTableReference(unsafe))).build())) {
            LootSafety safety = new LootSafety(level, ResourceKey.create(Registries.LOOT_TABLE, ModpackAssistant.id("safety_probe")), table, 0);
            boolean rejected = false;
            try {
                for (int step = 0; step < 100 && !safety.step(); step++) {}
            } catch (IllegalArgumentException expected) {
                rejected = expected.getMessage().contains("exploration_map");
            }
            helper.assertTrue(rejected && !safety.complete(), "exploration maps must be rejected before any rolls");
        }
        var safe = ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/village/village_plains_house"));
        LootSafety safety = new LootSafety(level, safe, 0);
        for (int step = 0; step < 100 && !safety.step(); step++) {}
        helper.assertTrue(safety.complete(), "ordinary village chest loot should be supported");
        helper.succeed();
    }

    public static void spawnPacksUseDeclaredRange(GameTestHelper helper) {
        RandomSource random = RandomSource.create(123);
        var singleton = new MobSpawnSettings.SpawnerData(EntityType.ZOMBIE, 1, 1);
        var large = new MobSpawnSettings.SpawnerData(EntityType.ZOMBIE, 6, 9);
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 1000; i++) {
            helper.assertTrue(SpawnSimulator.groupSize(singleton, random) == 1, "singleton pack must remain one");
            int size = SpawnSimulator.groupSize(large, random);
            helper.assertTrue(size >= 6 && size <= 9, "pack outside declared range");
            seen.add(size);
        }
        helper.assertTrue(seen.contains(6) && seen.contains(9), "pack range should be inclusive");
        helper.succeed();
    }

    public static void oversizedClipboardIsRejectedBeforeSending(GameTestHelper helper) throws Exception {
        var player = CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8));
        helper.assertFalse(MANetworking.sendClipboard(player, "x".repeat(SetClipboardPayload.MAX_TEXT_LENGTH + 1)), "oversized payload must not be sent");
        var buffer = Unpooled.buffer();
        try {
            String text = "é".repeat(SetClipboardPayload.MAX_TEXT_LENGTH);
            SetClipboardPayload.STREAM_CODEC.encode(buffer, new SetClipboardPayload(text));
            helper.assertTrue(SetClipboardPayload.STREAM_CODEC.decode(buffer).text().equals(text), "boundary payload must round trip");
        } finally {
            buffer.release();
        }
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(SetClipboardPayload.MAX_TEXT_LENGTH)));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        int result = helper.getLevel().getServer().getCommands().getDispatcher().execute("mpa copy hand", CoreGameTests.source(player));
        helper.assertTrue(result == 0, "oversized clipboard command must report failure");
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    public static void teleportRejectsFluidVoidAndPassengerObstructions(GameTestHelper helper) {
        var player = CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8));
        BlockPos relative = new BlockPos(4, 2, 4);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative.below(), Blocks.STONE);
        helper.setBlock(relative, Blocks.AIR);
        helper.setBlock(relative.above(), Blocks.AIR);
        helper.assertTrue(TpdCommand.safePosition(helper.getLevel(), pos, player), "supported dry position should be safe");
        helper.setBlock(relative, Blocks.LAVA);
        helper.assertFalse(TpdCommand.safePosition(helper.getLevel(), pos, player), "lava is unsafe");
        helper.setBlock(relative, Blocks.AIR);
        helper.setBlock(relative.below(), Blocks.AIR);
        helper.assertFalse(TpdCommand.safePosition(helper.getLevel(), pos, player), "unsupported air is unsafe");
        helper.setBlock(relative.below(), Blocks.MAGMA_BLOCK);
        helper.assertFalse(TpdCommand.safePosition(helper.getLevel(), pos, player), "hazardous support is unsafe");
        helper.succeed();
    }

    public static void miningDropsPreserveComponentVariants(GameTestHelper helper) {
        DropAccumulator accumulator = new DropAccumulator();
        ItemStack red = new ItemStack(Items.DIAMOND, 7);
        red.set(DataComponents.CUSTOM_NAME, Component.literal("red"));
        red.set(DataComponents.MAX_STACK_SIZE, 1);
        ItemStack blue = new ItemStack(Items.DIAMOND, 2);
        blue.set(DataComponents.CUSTOM_NAME, Component.literal("blue"));
        accumulator.add(red);
        accumulator.add(red.copyWithCount(3));
        accumulator.add(blue);
        helper.assertTrue(accumulator.total() == 12 && accumulator.ranked().size() == 2, "variants must not merge");
        var first = accumulator.ranked().getFirst();
        helper.assertTrue(first.count() == 10 && first.stack().getMaxStackSize() == 1, "count and component-aware stack size must survive");
        helper.assertTrue(ItemStack.isSameItemSameComponents(first.stack(), red), "representative stack must retain components");
        DropAccumulator limited = new DropAccumulator(1);
        limited.add(red);
        boolean rejected = false;
        try {
            limited.add(blue);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected && limited.ranked().size() == 1, "variant budget must prevent unlimited retention");
        helper.succeed();
    }

    public static void reportFilesAreUniqueAndNeverOverwrite(GameTestHelper helper) throws Exception {
        var first = ReportWriter.file(ReportWriter.Family.REGISTRY, "collision_probe", "txt");
        var second = ReportWriter.file(ReportWriter.Family.REGISTRY, "collision_probe", "txt");
        helper.assertFalse(first.equals(second), "same-time reports must have distinct paths");
        ReportWriter.writeString(first, "first");
        boolean rejected = false;
        try {
            ReportWriter.writeString(first, "replacement");
        } catch (FileAlreadyExistsException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected && Files.readString(first).equals("first"), "existing reports must not be overwritten");
        ReportWriter.writeLines(second, List.of("second"));
        helper.succeed();
    }

    public static void entityRemovalIsBoundedAndCancellable(GameTestHelper helper) throws Exception {
        helper.assertFalse(RunScheduler.isBusy(), "scheduler should be idle");
        List<Creeper> entities = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            Creeper entity = helper.spawn(EntityType.CREEPER, new BlockPos(4, 1, 4));
            entity.setNoAi(true);
            entities.add(entity);
        }
        CommandSourceStack source = CoreGameTests.source(CoreGameTests.fakePlayer(helper, new BlockPos(8, 1, 8)));
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        helper.assertTrue(dispatcher.execute("mpa kill by minecraft:creeper", source) > 0, "kill run should start");
        helper.assertTrue(dispatcher.execute("mpa kill items", source) == 0, "overlapping run should be refused");
        helper.runAfterDelay(6, () -> {
            long removed = entities.stream().filter(Creeper::isRemoved).count();
            helper.assertTrue(removed > 0 && removed <= 128, "one batch must remove at most 128 entities");
            helper.assertTrue(RunScheduler.cancel(source), "partial kill should remain cancellable");
            entities.forEach(Creeper::discard);
            helper.succeed();
        });
    }
}
