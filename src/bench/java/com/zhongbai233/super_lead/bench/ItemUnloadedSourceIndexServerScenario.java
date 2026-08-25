package com.zhongbai233.super_lead.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.server.BenchServerContext;
import com.zhongbai233.bench.api.neoforge.server.BenchServerScenario;
import com.zhongbai233.bench.api.neoforge.server.BenchStepResult;
import com.zhongbai233.super_lead.lead.LeadAnchor;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadPayloads;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Profiles the steady-state ITEM source index when all indexed source chunks are
 * unloaded.
 *
 * <p>The fixture is inserted directly into SavedData so setup never generates or
 * loads the distant chunks. There are many distinct source positions per chunk,
 * which makes the JFR distinguish a per-position scan from the chunk-grouped
 * fast path without involving any container capability implementation.
 */
final class ItemUnloadedSourceIndexServerScenario implements BenchServerScenario {
    private static final int SOURCE_CHUNKS = 64;
    private static final int SOURCES_PER_CHUNK = 64;
    private static final int TOTAL_CONNECTIONS = SOURCE_CHUNKS * SOURCES_PER_CHUNK;
    private static final int MEASURE_TICKS = 600;
    private static final int CHUNK_GRID_WIDTH = 8;
    private static final int CHUNK_STRIDE = 4;
    private static final int BASE_CHUNK_X = 400_000;
    private static final int BASE_CHUNK_Z = 400_000;

    private static final BenchMetricDescriptor INDEXED_CONNECTIONS = new BenchMetricDescriptor(
            "super_lead.item_unloaded_index.connections", "connections", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor LOADED_SOURCE_CHUNKS = new BenchMetricDescriptor(
            "super_lead.item_unloaded_index.loaded_source_chunks", "chunks", MetricDirection.LOWER_IS_BETTER);

    private final List<UUID> connectionIds = new ArrayList<>(TOTAL_CONNECTIONS);
    private final List<ChunkPos> sourceChunks = new ArrayList<>(SOURCE_CHUNKS);
    private ServerLevel level;
    private int measuredTicks;

    @Override
    public void setup(BenchServerContext context) {
        level = context.level();
        SuperLeadSavedData data = SuperLeadSavedData.get(level);

        for (int chunkIndex = 0; chunkIndex < SOURCE_CHUNKS; chunkIndex++) {
            int chunkX = BASE_CHUNK_X + (chunkIndex % CHUNK_GRID_WIDTH) * CHUNK_STRIDE;
            int chunkZ = BASE_CHUNK_Z + (chunkIndex / CHUNK_GRID_WIDTH) * CHUNK_STRIDE;
            ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
            sourceChunks.add(chunk);

            for (int sourceIndex = 0; sourceIndex < SOURCES_PER_CHUNK; sourceIndex++) {
                int localX = 2 + (sourceIndex & 7);
                int localZ = 2 + (sourceIndex >>> 3);
                BlockPos source = new BlockPos(chunkX * 16 + localX, 80, chunkZ * 16 + localZ);
                BlockPos target = source.above();
                LeadConnection connection = LeadConnection.create(
                        new LeadAnchor(source, Direction.UP),
                        new LeadAnchor(target, Direction.DOWN),
                        LeadKind.ITEM).withExtractAnchor(1);
                data.add(connection);
                connectionIds.add(connection.id());
            }
        }

        // Keep fixture publication/setup work out of the measured steady-state window.
        SuperLeadPayloads.sendDirtyToDimension(level);
        assertFixturePresent(data);
        assertSourceChunksUnloaded();
    }

    @Override
    public BenchStepResult measure(BenchServerContext context) {
        measuredTicks++;
        return measuredTicks >= MEASURE_TICKS ? BenchStepResult.COMPLETE : BenchStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchServerContext context) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        assertFixturePresent(data);
        int loadedChunks = loadedSourceChunkCount();
        if (loadedChunks != 0) {
            throw new AssertionError("ITEM unloaded-index fixture loaded source chunks during measurement: "
                    + loadedChunks + "/" + SOURCE_CHUNKS);
        }
        context.metrics().record(INDEXED_CONNECTIONS, connectionIds.size());
        context.metrics().record(LOADED_SOURCE_CHUNKS, loadedChunks);
    }

    @Override
    public void teardown(BenchServerContext context) {
        if (level == null || connectionIds.isEmpty()) {
            return;
        }
        Set<UUID> ids = Set.copyOf(connectionIds);
        SuperLeadSavedData.get(level).removeIf(connection -> ids.contains(connection.id()));
        SuperLeadPayloads.sendDirtyToDimension(level);
    }

    private void assertFixturePresent(SuperLeadSavedData data) {
        int present = 0;
        for (UUID id : connectionIds) {
            if (data.find(id).isPresent()) {
                present++;
            }
        }
        if (connectionIds.size() != TOTAL_CONNECTIONS || present != TOTAL_CONNECTIONS) {
            throw new AssertionError("ITEM unloaded-index fixture changed unexpectedly: created="
                    + connectionIds.size() + " present=" + present + " expected=" + TOTAL_CONNECTIONS);
        }
    }

    private void assertSourceChunksUnloaded() {
        int loadedChunks = loadedSourceChunkCount();
        if (loadedChunks != 0) {
            throw new IllegalStateException("ITEM unloaded-index setup touched " + loadedChunks
                    + " distant source chunks");
        }
    }

    private int loadedSourceChunkCount() {
        int loaded = 0;
        for (ChunkPos chunk : sourceChunks) {
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                loaded++;
            }
        }
        return loaded;
    }
}
