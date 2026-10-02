package com.lhawk.antixray;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkDataBulk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkManager implements Listener {

    private final AntiXray plugin;
    private final AtomicLong chunksProcessed = new AtomicLong();
    private final AtomicLong chunksModified = new AtomicLong();
    private final AtomicLong oresHidden = new AtomicLong();

    private final Map<Long, ChunkBorders> borderCache = Collections.synchronizedMap(
        new LinkedHashMap<Long, ChunkBorders>(512, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkBorders> eldest) {
                return size() > 512;
            }
        }
    );

    public ChunkManager(AntiXray plugin) {
        this.plugin = plugin;
    }

    public long getChunksProcessed() { return chunksProcessed.get(); }
    public long getChunksModified() { return chunksModified.get(); }
    public long getOresHidden() { return oresHidden.get(); }
    public int getCachedChunkCount() { return borderCache.size(); }

    public boolean processChunk(User user, Player player, WrapperPlayServerChunkData wrapper) {
        Column column = wrapper.getColumn();
        if (column == null) return false;
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return false;

        World world = player.getWorld();
        int chunkX = column.getX();
        int chunkZ = column.getZ();
        int minHeight = 0;
        try {
            minHeight = world.getMinHeight();
        } catch (Throwable ignored) {
        }
        int minSection = minHeight >> 4;
        ClientVersion clientVersion = user.getClientVersion();
        World.Environment env = world.getEnvironment();

        ChunkBorders borders = new ChunkBorders(sections.length, minSection);
        boolean modified = false;

        for (int s = 0; s < sections.length; s++) {
            BaseChunk chunk = sections[s];
            if (chunk == null || chunk.isEmpty()) continue;

            int secY = minSection + s;
            int baseY = secY << 4;
            BaseChunk chunkAbove = (s + 1 < sections.length) ? sections[s + 1] : null;
            BaseChunk chunkBelow = (s > 0) ? sections[s - 1] : null;

            for (int y = 0; y < 16; y++) {
                int worldY = baseY + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int blockId = chunk.getBlockId(x, y, z);
                        boolean occluding = plugin.getCompatibility().isOccluding(blockId);
                        if (x == 0 || x == 15 || z == 0 || z == 15) {
                            borders.setOccluding(secY, x, y, z, occluding);
                        }

                        if (!plugin.getCompatibility().isOre(blockId)) continue;

                        int worldX = (chunkX << 4) + x;
                        int worldZ = (chunkZ << 4) + z;

                        if (plugin.getBlockManager().isRevealed(player, worldX, worldY, worldZ)) continue;

                        boolean exposed = false;

                        if (x < 15) {
                            if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x + 1, y, z))) exposed = true;
                        } else {
                            if (!isNeighborOccluding(chunkX + 1, chunkZ, 0, worldY, z)) exposed = true;
                        }
                        if (exposed) continue;

                        if (x > 0) {
                            if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x - 1, y, z))) exposed = true;
                        } else {
                            if (!isNeighborOccluding(chunkX - 1, chunkZ, 15, worldY, z)) exposed = true;
                        }
                        if (exposed) continue;

                        if (z < 15) {
                            if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y, z + 1))) exposed = true;
                        } else {
                            if (!isNeighborOccluding(chunkX, chunkZ + 1, x, worldY, 0)) exposed = true;
                        }
                        if (exposed) continue;

                        if (z > 0) {
                            if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y, z - 1))) exposed = true;
                        } else {
                            if (!isNeighborOccluding(chunkX, chunkZ - 1, x, worldY, 15)) exposed = true;
                        }
                        if (exposed) continue;

                        if (y < 15) {
                            if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y + 1, z))) exposed = true;
                        } else if (chunkAbove != null && !chunkAbove.isEmpty()) {
                            if (!plugin.getCompatibility().isOccluding(chunkAbove.getBlockId(x, 0, z))) exposed = true;
                        } else {
                            exposed = true;
                        }
                        if (exposed) continue;

                        if (y > 0) {
                            if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y - 1, z))) exposed = true;
                        } else if (chunkBelow != null && !chunkBelow.isEmpty()) {
                            if (!plugin.getCompatibility().isOccluding(chunkBelow.getBlockId(x, 15, z))) exposed = true;
                        }
                        if (exposed) continue;

                        WrappedBlockState repl = plugin.getCompatibility().getReplacementState(blockId, worldY, env, clientVersion);
                        chunk.set(x, y, z, repl);
                        modified = true;
                        oresHidden.incrementAndGet();
                    }
                }
            }
        }

        long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        borderCache.put(chunkKey, borders);

        chunksProcessed.incrementAndGet();
        if (modified) {
            chunksModified.incrementAndGet();
            wrapper.setColumn(column);
        }
        return modified;
    }

    public boolean processChunkBulk(User user, Player player, WrapperPlayServerChunkDataBulk bulk) {
        int[] xs = bulk.getX();
        int[] zs = bulk.getZ();
        BaseChunk[][] allChunks = bulk.getChunks();
        if (xs == null || zs == null || allChunks == null) return false;

        World world = player.getWorld();
        int minHeight = 0;
        try {
            minHeight = world.getMinHeight();
        } catch (Throwable ignored) {
        }
        int minSection = minHeight >> 4;
        ClientVersion clientVersion = user.getClientVersion();
        World.Environment env = world.getEnvironment();
        boolean anyModified = false;

        for (int c = 0; c < allChunks.length; c++) {
            BaseChunk[] sections = allChunks[c];
            if (sections == null) continue;
            int chunkX = xs[c];
            int chunkZ = zs[c];
            ChunkBorders borders = new ChunkBorders(sections.length, minSection);

            for (int s = 0; s < sections.length; s++) {
                BaseChunk chunk = sections[s];
                if (chunk == null || chunk.isEmpty()) continue;

                int secY = minSection + s;
                int baseY = secY << 4;
                BaseChunk chunkAbove = (s + 1 < sections.length) ? sections[s + 1] : null;
                BaseChunk chunkBelow = (s > 0) ? sections[s - 1] : null;

                for (int y = 0; y < 16; y++) {
                    int worldY = baseY + y;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int blockId = chunk.getBlockId(x, y, z);
                            boolean occluding = plugin.getCompatibility().isOccluding(blockId);
                            if (x == 0 || x == 15 || z == 0 || z == 15) {
                                borders.setOccluding(secY, x, y, z, occluding);
                            }

                            if (!plugin.getCompatibility().isOre(blockId)) continue;

                            int worldX = (chunkX << 4) + x;
                            int worldZ = (chunkZ << 4) + z;

                            if (plugin.getBlockManager().isRevealed(player, worldX, worldY, worldZ)) continue;

                            boolean exposed = false;

                            if (x < 15) {
                                if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x + 1, y, z))) exposed = true;
                            } else {
                                if (!isNeighborOccluding(chunkX + 1, chunkZ, 0, worldY, z)) exposed = true;
                            }
                            if (exposed) continue;

                            if (x > 0) {
                                if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x - 1, y, z))) exposed = true;
                            } else {
                                if (!isNeighborOccluding(chunkX - 1, chunkZ, 15, worldY, z)) exposed = true;
                            }
                            if (exposed) continue;

                            if (z < 15) {
                                if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y, z + 1))) exposed = true;
                            } else {
                                if (!isNeighborOccluding(chunkX, chunkZ + 1, x, worldY, 0)) exposed = true;
                            }
                            if (exposed) continue;

                            if (z > 0) {
                                if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y, z - 1))) exposed = true;
                            } else {
                                if (!isNeighborOccluding(chunkX, chunkZ - 1, x, worldY, 15)) exposed = true;
                            }
                            if (exposed) continue;

                            if (y < 15) {
                                if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y + 1, z))) exposed = true;
                            } else if (chunkAbove != null && !chunkAbove.isEmpty()) {
                                if (!plugin.getCompatibility().isOccluding(chunkAbove.getBlockId(x, 0, z))) exposed = true;
                            } else {
                                exposed = true;
                            }
                            if (exposed) continue;

                            if (y > 0) {
                                if (!plugin.getCompatibility().isOccluding(chunk.getBlockId(x, y - 1, z))) exposed = true;
                            } else if (chunkBelow != null && !chunkBelow.isEmpty()) {
                                if (!plugin.getCompatibility().isOccluding(chunkBelow.getBlockId(x, 15, z))) exposed = true;
                            }
                            if (exposed) continue;

                            WrappedBlockState repl = plugin.getCompatibility().getReplacementState(blockId, worldY, env, clientVersion);
                            chunk.set(x, y, z, repl);
                            anyModified = true;
                            oresHidden.incrementAndGet();
                        }
                    }
                }
            }

            long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
            borderCache.put(chunkKey, borders);
        }

        chunksProcessed.incrementAndGet();
        if (anyModified) {
            chunksModified.incrementAndGet();
        }
        return anyModified;
    }

    private boolean isNeighborOccluding(int nChunkX, int nChunkZ, int borderX, int worldY, int borderZ) {
        long key = (((long) nChunkX) << 32) | (nChunkZ & 0xFFFFFFFFL);
        ChunkBorders borders = borderCache.get(key);
        if (borders == null) return true;
        return borders.isOccluding(borderX, worldY, borderZ);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        long key = (((long) event.getChunk().getX()) << 32) | (event.getChunk().getZ() & 0xFFFFFFFFL);
        borderCache.remove(key);
    }

    private static class ChunkBorders {
        private final int minSection;
        private final long[][] east;
        private final long[][] west;
        private final long[][] south;
        private final long[][] north;

        ChunkBorders(int sectionCount, int minSection) {
            this.minSection = minSection;
            this.east = new long[sectionCount][4];
            this.west = new long[sectionCount][4];
            this.south = new long[sectionCount][4];
            this.north = new long[sectionCount][4];
        }

        void setOccluding(int s, int localX, int localY, int localZ, boolean occluding) {
            int secIdx = s - minSection;
            if (secIdx < 0 || secIdx >= east.length) return;
            if (!occluding) return;

            int bit = (localY << 4) | (localX == 0 || localX == 15 ? localZ : localX);
            int word = bit >> 6;
            long mask = 1L << (bit & 63);

            if (localX == 15) east[secIdx][word] |= mask;
            if (localX == 0) west[secIdx][word] |= mask;
            if (localZ == 15) south[secIdx][word] |= mask;
            if (localZ == 0) north[secIdx][word] |= mask;
        }

        boolean isOccluding(int localX, int worldY, int localZ) {
            int sec = worldY >> 4;
            int secIdx = sec - minSection;
            if (secIdx < 0 || secIdx >= east.length) return true;
            int localY = worldY & 15;
            int bit = (localY << 4) | (localX == 0 || localX == 15 ? localZ : localX);
            int word = bit >> 6;
            long mask = 1L << (bit & 63);

            if (localX == 15) return (east[secIdx][word] & mask) != 0;
            if (localX == 0) return (west[secIdx][word] & mask) != 0;
            if (localZ == 15) return (south[secIdx][word] & mask) != 0;
            if (localZ == 0) return (north[secIdx][word] & mask) != 0;
            return true;
        }
    }
}
