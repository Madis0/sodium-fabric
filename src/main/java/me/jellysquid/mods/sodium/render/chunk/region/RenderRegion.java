package me.jellysquid.mods.sodium.render.chunk.region;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import me.jellysquid.mods.sodium.SodiumRender;
import me.jellysquid.mods.sodium.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.util.MathUtil;
import net.minecraft.util.math.ChunkSectionPos;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class RenderRegion {
    public static final int REGION_WIDTH = 8;
    public static final int REGION_HEIGHT = 4;
    public static final int REGION_LENGTH = 8;

    public static final int REGION_WIDTH_M = RenderRegion.REGION_WIDTH - 1;
    public static final int REGION_HEIGHT_M = RenderRegion.REGION_HEIGHT - 1;
    public static final int REGION_LENGTH_M = RenderRegion.REGION_LENGTH - 1;

    private static final int REGION_WIDTH_SH = Integer.bitCount(REGION_WIDTH_M);
    private static final int REGION_HEIGHT_SH = Integer.bitCount(REGION_HEIGHT_M);
    private static final int REGION_LENGTH_SH = Integer.bitCount(REGION_LENGTH_M);

    public static final int REGION_SIZE = REGION_WIDTH * REGION_HEIGHT * REGION_LENGTH;

    static {
        Validate.isTrue(MathUtil.isPowerOfTwo(REGION_WIDTH));
        Validate.isTrue(MathUtil.isPowerOfTwo(REGION_HEIGHT));
        Validate.isTrue(MathUtil.isPowerOfTwo(REGION_LENGTH));
    }

    private final Set<RenderSection> chunks = new ObjectOpenHashSet<>();
    private final Map<BlockRenderPass, RenderRegionStorage> storage = new Reference2ObjectArrayMap<>();

    private final int x, y, z;

    public RenderRegion(int x, int y, int z) {

        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static RenderRegion createRegionForChunk(int x, int y, int z) {
        return new RenderRegion(x >> REGION_WIDTH_SH, y >> REGION_HEIGHT_SH, z >> REGION_LENGTH_SH);
    }

    public RenderRegionStorage initStorage(BlockRenderPass pass) {
        RenderRegionStorage storage = this.storage.get(pass);

        if (storage == null) {
            this.storage.put(pass, storage = new RenderRegionStorage(SodiumRender.DEVICE));
        }

        return storage;
    }

    @Nullable
    public RenderRegionStorage getStorage(BlockRenderPass pass) {
        return this.storage.get(pass);
    }

    public void deleteResources() {
        for (RenderRegionStorage arena : this.storage.values()) {
            arena.delete();
        }

        this.storage.clear();
    }

    public static long getRegionKeyForChunk(int x, int y, int z) {
        return ChunkSectionPos.asLong(x >> REGION_WIDTH_SH, y >> REGION_HEIGHT_SH, z >> REGION_LENGTH_SH);
    }

    public int getOriginX() {
        return this.x << REGION_WIDTH_SH << 4;
    }

    public int getOriginY() {
        return this.y << REGION_HEIGHT_SH << 4;
    }

    public int getOriginZ() {
        return this.z << REGION_LENGTH_SH << 4;
    }

    public void addChunk(RenderSection chunk) {
        if (!this.chunks.add(chunk)) {
            throw new IllegalStateException("Chunk " + chunk + " is already a member of region " + this);
        }
    }

    public void removeChunk(RenderSection chunk) {
        if (!this.chunks.remove(chunk)) {
            throw new IllegalStateException("Chunk " + chunk + " is not a member of region " + this);
        }
    }

    public boolean isEmpty() {
        return this.chunks.isEmpty();
    }

    public int getChunkCount() {
        return this.chunks.size();
    }

    public static short getChunkIndex(int x, int y, int z) {
        return (short) ((x * RenderRegion.REGION_LENGTH * RenderRegion.REGION_HEIGHT) + (y * RenderRegion.REGION_LENGTH) + z);
    }

    public Collection<RenderRegionStorage> getAllStorage() {
        return this.storage.values();
    }

    public void deleteUnusedStorage() {
        for (Iterator<RenderRegionStorage> iterator = this.storage.values().iterator(); iterator.hasNext(); ) {
            RenderRegionStorage storage = iterator.next();

            if (storage.isEmpty()) {
                storage.delete();
                iterator.remove();
            }
        }
    }
}
