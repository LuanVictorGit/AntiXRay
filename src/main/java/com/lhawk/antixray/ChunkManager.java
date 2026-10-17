package com.lhawk.antixray;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkDataBulk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkManager implements Listener {

    private static final int MAX_CACHED_CHUNKS = 2048;

    private static final int BLOCKED = 0;
    private static final int OPEN = 1;
    private static final int UNKNOWN = 2;

    private static final int ENCLOSED = 0;
    private static final int EXPOSED = 1;

    private final AntiXray plugin;
    private final AtomicLong chunksProcessed = new AtomicLong();
    private final AtomicLong chunksModified = new AtomicLong();
    private final AtomicLong blocksHidden = new AtomicLong();
    private final Map<UUID, Map<Long, ChunkBorders>> borderCache = new ConcurrentHashMap<UUID, Map<Long, ChunkBorders>>();

    public ChunkManager(AntiXray plugin) {
        this.plugin = plugin;
    }

    public long getChunksProcessed() { return chunksProcessed.get(); }
    public long getChunksModified() { return chunksModified.get(); }
    public long getBlocksHidden() { return blocksHidden.get(); }

    public boolean processChunk(Player player, WrapperPlayServerChunkData wrapper) {
        Column column = wrapper.getColumn();
        if (column == null) return false;
        return processColumn(player, column.getX(), column.getZ(), column.getChunks());
    }

    public boolean processChunkBulk(Player player, WrapperPlayServerChunkDataBulk bulk) {
        int[] xs = bulk.getX();
        int[] zs = bulk.getZ();
        BaseChunk[][] columns = bulk.getChunks();
        if (xs == null || zs == null || columns == null) return false;

        boolean modified = false;
        for (int i = 0; i < columns.length; i++) {
            if (processColumn(player, xs[i], zs[i], columns[i])) modified = true;
        }
        return modified;
    }

    private boolean processColumn(Player player, int chunkX, int chunkZ, BaseChunk[] sections) {
        if (sections == null || sections.length == 0) return false;

        World world = player.getWorld();
        World.Environment env = world.getEnvironment();
        int minSection = getMinHeight(world) >> 4;
        Map<Long, ChunkBorders> cache = getBorderCache(world);
        Compatibility compat = plugin.getCompatibility();
        boolean proximity = plugin.getConfiguration().getProximityDistance() > 0;
        ChunkBorders borders = new ChunkBorders(sections.length);
        List<Long> exposedOres = null;
        boolean modified = false;

        for (int s = 0; s < sections.length; s++) {
            BaseChunk section = sections[s];
            if (section == null || section.isEmpty()) continue;
            int baseY = (minSection + s) << 4;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int blockId = section.getBlockId(x, y, z);
                        if (x == 0 || x == 15 || z == 0 || z == 15) {
                            borders.set(s, x, y, z, compat.isOccluding(blockId));
                        }

                        if (!compat.isOre(blockId)) continue;

                        int worldX = (chunkX << 4) + x;
                        int worldY = baseY + y;
                        int worldZ = (chunkZ << 4) + z;
                        int state = enclosure(cache, sections, s, chunkX, chunkZ, x, y, z);
                        if (state == EXPOSED && !proximity) continue;
                        if (proximity && state != ENCLOSED) {
                            if (exposedOres == null) exposedOres = new ArrayList<Long>();
                            exposedOres.add(BlockManager.pack(worldX, worldY, worldZ));
                        }

                        section.set(x, y, z, compat.getReplacementId(blockId, worldY, env));
                        modified = true;
                        blocksHidden.incrementAndGet();
                    }
                }
            }
        }

        cache.put(chunkKey(chunkX, chunkZ), borders);
        plugin.getBlockManager().setHiddenOres(player, chunkX, chunkZ, exposedOres);
        chunksProcessed.incrementAndGet();
        if (modified) chunksModified.incrementAndGet();
        return modified;
    }

    /**
     * Classifies an ore by its six faces. An ore is ENCLOSED when every face is solid,
     * EXPOSED when one face opens onto a non-solid block, and UNKNOWN when no face is open
     * but a face points into a neighbouring chunk that has not been received yet.
     */
    private int enclosure(Map<Long, ChunkBorders> cache, BaseChunk[] sections, int s, int chunkX, int chunkZ, int x, int y, int z) {
        boolean unknown = false;
        for (int dir = 0; dir < 6; dir++) {
            int face = face(cache, sections, s, chunkX, chunkZ, x, y, z, dir);
            if (face == OPEN) return EXPOSED;
            if (face == UNKNOWN) unknown = true;
        }
        return unknown ? UNKNOWN : ENCLOSED;
    }

    private int face(Map<Long, ChunkBorders> cache, BaseChunk[] sections, int s, int chunkX, int chunkZ, int x, int y, int z, int dir) {
        Compatibility c = plugin.getCompatibility();
        BaseChunk section = sections[s];
        switch (dir) {
            case 0: return x < 15 ? faceOf(c, section.getBlockId(x + 1, y, z)) : neighborFace(cache, chunkX + 1, chunkZ, s, 0, y, z);
            case 1: return x > 0 ? faceOf(c, section.getBlockId(x - 1, y, z)) : neighborFace(cache, chunkX - 1, chunkZ, s, 15, y, z);
            case 2: return z < 15 ? faceOf(c, section.getBlockId(x, y, z + 1)) : neighborFace(cache, chunkX, chunkZ + 1, s, x, y, 0);
            case 3: return z > 0 ? faceOf(c, section.getBlockId(x, y, z - 1)) : neighborFace(cache, chunkX, chunkZ - 1, s, x, y, 15);
            case 4: return y < 15 ? faceOf(c, section.getBlockId(x, y + 1, z)) : sectionFace(c, sections, s + 1, x, 0, z);
            default: return y > 0 ? faceOf(c, section.getBlockId(x, y - 1, z)) : (s == 0 ? BLOCKED : sectionFace(c, sections, s - 1, x, 15, z));
        }
    }

    private static int faceOf(Compatibility c, int blockId) {
        return c.isOccluding(blockId) ? BLOCKED : OPEN;
    }

    private static int sectionFace(Compatibility c, BaseChunk[] sections, int s, int x, int y, int z) {
        if (s >= sections.length) return OPEN;
        BaseChunk section = sections[s];
        if (section == null || section.isEmpty()) return OPEN;
        return faceOf(c, section.getBlockId(x, y, z));
    }

    private int neighborFace(Map<Long, ChunkBorders> cache, int chunkX, int chunkZ, int s, int x, int y, int z) {
        ChunkBorders borders = cache.get(chunkKey(chunkX, chunkZ));
        if (borders == null) return UNKNOWN;
        return borders.isOccluding(s, x, y, z) ? BLOCKED : OPEN;
    }

    private Map<Long, ChunkBorders> getBorderCache(World world) {
        Map<Long, ChunkBorders> cache = borderCache.get(world.getUID());
        if (cache == null) {
            cache = Collections.synchronizedMap(new LinkedHashMap<Long, ChunkBorders>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ChunkBorders> eldest) {
                    return size() > MAX_CACHED_CHUNKS;
                }
            });
            Map<Long, ChunkBorders> existing = borderCache.putIfAbsent(world.getUID(), cache);
            if (existing != null) cache = existing;
        }
        return cache;
    }

    private static int getMinHeight(World world) {
        try {
            return world.getMinHeight();
        } catch (Throwable t) {
            return 0;
        }
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Map<Long, ChunkBorders> cache = borderCache.get(event.getWorld().getUID());
        if (cache != null) {
            cache.remove(chunkKey(event.getChunk().getX(), event.getChunk().getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        borderCache.remove(event.getWorld().getUID());
    }

    private static class ChunkBorders {
        private final long[] west;
        private final long[] east;
        private final long[] north;
        private final long[] south;

        ChunkBorders(int sectionCount) {
            this.west = new long[sectionCount * 4];
            this.east = new long[sectionCount * 4];
            this.north = new long[sectionCount * 4];
            this.south = new long[sectionCount * 4];
        }

        void set(int s, int x, int y, int z, boolean occluding) {
            if (!occluding) return;
            if (x == 0) setBit(west, s, y, z);
            if (x == 15) setBit(east, s, y, z);
            if (z == 0) setBit(north, s, y, x);
            if (z == 15) setBit(south, s, y, x);
        }

        boolean isOccluding(int s, int x, int y, int z) {
            if (s < 0 || s * 4 >= west.length) return true;
            if (x == 0) return getBit(west, s, y, z);
            if (x == 15) return getBit(east, s, y, z);
            if (z == 0) return getBit(north, s, y, x);
            return getBit(south, s, y, x);
        }

        private static void setBit(long[] bits, int s, int y, int i) {
            int bit = (s << 8) | (y << 4) | i;
            bits[bit >> 6] |= 1L << (bit & 63);
        }

        private static boolean getBit(long[] bits, int s, int y, int i) {
            int bit = (s << 8) | (y << 4) | i;
            return (bits[bit >> 6] & (1L << (bit & 63))) != 0;
        }
    }
}
