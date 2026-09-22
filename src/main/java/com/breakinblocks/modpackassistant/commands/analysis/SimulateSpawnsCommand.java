package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.analysis.SpawnSimulator;
import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class SimulateSpawnsCommand {
    private static final int SAMPLE_CHUNK_RADIUS = 8;
    private static final int CHAT_LINES = 10;
    private static final int TICKS_PER_DAY = 24_000;
    private static final int TICKS_PER_MINUTE = 1_200;

    private SimulateSpawnsCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("simulateSpawns")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("biome", ResourceArgument.resource(buildContext, Registries.BIOME))
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(SimulateSpawnsCommand::simulate))));
    }

    private static int simulate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        Holder.Reference<Biome> biome = ResourceArgument.getResource(context, "biome", Registries.BIOME);
        ServerLevel level = DimensionArgument.getDimension(context, "dimension");
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        if (ticks > MAConfig.maxSimulatedTicks()) {
            return CommandResults.fail(source, Messages.TOO_MANY_ITERATIONS.get(ticks, MAConfig.maxSimulatedTicks(), "max_simulated_ticks"));
        }

        boolean sameDimension = level == player.level();
        BlockPos anchor = sameDimension ? player.blockPosition() : level.getRespawnData().pos();
        ChunkPos anchorChunk = ChunkPos.containing(anchor);
        List<ChunkPos> matching = new ArrayList<>();
        TreeSet<String> present = new TreeSet<>();
        for (int x = -SAMPLE_CHUNK_RADIUS; x <= SAMPLE_CHUNK_RADIUS; x++) {
            for (int z = -SAMPLE_CHUNK_RADIUS; z <= SAMPLE_CHUNK_RADIUS; z++) {
                ChunkPos chunk = new ChunkPos(anchorChunk.x() + x, anchorChunk.z() + z);
                if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, chunk.getMiddleBlockPosition(0));
                Holder<Biome> at = level.getBiome(surface);
                at.unwrapKey().map(ResourceKey::identifier).map(Identifier::toString).ifPresent(present::add);
                if (at.is(biome.key())) {
                    matching.add(chunk);
                }
            }
        }
        Identifier biomeId = biome.key().identifier();
        if (matching.isEmpty()) {
            return CommandResults.fail(source, Messages.SPAWNS_NO_BIOME.get(biomeId, level.dimension().identifier(), String.join(", ", present)));
        }

        Vec3 virtualPlayer = sameDimension ? player.position() : Vec3.atCenterOf(anchor);
        SpawnSimulator simulator = new SpawnSimulator(level, biome, matching, virtualPlayer, ticks);
        ReportWriter.Context reportContext = new ReportWriter.Context(source, "/mpa simulateSpawns " + biomeId + " " + level.dimension().identifier() + " " + ticks)
                .note("biome", biomeId)
                .note("target_dimension", level.dimension().identifier())
                .note("sampled_chunks", matching.size())
                .note("simulated_ticks", ticks)
                .note("virtual_player", virtualPlayer);

        Run run = new Run(source, "spawn simulation", level.dimension());
        run.repeat(() -> {
            simulator.simulateBatch();
            return simulator.complete();
        });
        run.onComplete(finished -> {
            finished.message(Messages.SPAWNS_HEADER.get(ticks, biomeId, simulator.totalIndividuals()).withStyle(ChatFormatting.GREEN));
            List<SpawnSimulator.TypeStat> ranked = simulator.rankedTypes();
            for (int i = 0; i < Math.min(CHAT_LINES, ranked.size()); i++) {
                SpawnSimulator.TypeStat stat = ranked.get(i);
                finished.message(Messages.SPAWNS_LINE.get(BuiltInRegistries.ENTITY_TYPE.getKey(stat.type), stat.individuals, stat.attempts));
            }
            finished.message(Messages.SPAWNS_SKIPPED.get(String.join(", ", SpawnSimulator.SKIPPED_RULES)).withStyle(ChatFormatting.GRAY));
            ReportWriter.deliver(finished, ReportWriter.Family.SPAWNS, "spawns-" + biomeId, "csv", simulator.csv(reportContext));
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.SPAWNS_START.get(ticks, String.format("%.2f", ticks / (double) TICKS_PER_DAY), String.format("%.1f", ticks / (double) TICKS_PER_MINUTE), biomeId, matching.size()));
        return run.total();
    }
}
