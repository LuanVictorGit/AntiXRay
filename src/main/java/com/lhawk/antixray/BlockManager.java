package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class BlockManager implements Listener {

    private static final long CHECK_INTERVAL_MS = 250;
    private static final int HIDE_MARGIN = 16;

    private final AntiXray plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<UUID, PlayerData>();
    private volatile boolean errorLogged;

    public BlockManager(AntiXray plugin) {
        this.plugin = plugin;
    }

    public static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) (y + 2048) & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL);
    }

    static int unpackX(long pos) { return (int) (pos >> 38); }
    static int unpackY(long pos) { return (int) ((pos >> 26) & 0xFFFL) - 2048; }
    static int unpackZ(long pos) { return (int) (pos << 38 >> 38); }

    private PlayerData getData(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) {
            data = new PlayerData();
            PlayerData existing = players.putIfAbsent(uuid, data);
            if (existing != null) data = existing;
        }
        return data;
    }

    public boolean isRevealed(Player player, int x, int y, int z) {
        if (player == null) return false;
        PlayerData data = players.get(player.getUniqueId());
        if (data == null) return false;
        ChunkOres chunk = data.chunks.get(ChunkManager.chunkKey(x >> 4, z >> 4));
        return chunk != null && chunk.revealed.contains(pack(x, y, z));
    }

    public void markRevealed(UUID uuid, int x, int y, int z) {
        PlayerData data = getData(uuid);
        long key = ChunkManager.chunkKey(x >> 4, z >> 4);
        ChunkOres chunk = data.chunks.computeIfAbsent(key, k -> new ChunkOres());
        chunk.revealed.add(pack(x, y, z));
    }

    public void unmarkRevealed(UUID uuid, int x, int y, int z) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        ChunkOres chunk = data.chunks.get(ChunkManager.chunkKey(x >> 4, z >> 4));
        if (chunk != null) {
            chunk.revealed.remove(pack(x, y, z));
        }
    }

    public void setHiddenOres(Player player, int chunkX, int chunkZ, List<Long> positions) {
        long key = ChunkManager.chunkKey(chunkX, chunkZ);
        if (positions == null || positions.isEmpty()) {
            forgetChunk(player, chunkX, chunkZ);
            return;
        }
        PlayerData data = getData(player.getUniqueId());
        ChunkOres chunk = new ChunkOres();
        chunk.hidden.addAll(positions);
        data.chunks.put(key, chunk);
        data.version.incrementAndGet();
    }

    public void addHiddenOre(Player player, int x, int y, int z) {
        PlayerData data = getData(player.getUniqueId());
        long key = ChunkManager.chunkKey(x >> 4, z >> 4);
        long pos = pack(x, y, z);
        ChunkOres chunk = data.chunks.computeIfAbsent(key, k -> new ChunkOres());
        if (chunk.hidden.add(pos)) {
            data.version.incrementAndGet();
        }
    }

    public void forgetChunk(Player player, int chunkX, int chunkZ) {
        PlayerData data = players.get(player.getUniqueId());
        if (data != null) {
            data.chunks.remove(ChunkManager.chunkKey(chunkX, chunkZ));
        }
    }

    public void clearPlayerCache(UUID uuid) {
        players.remove(uuid);
    }

    public int getCachedPlayerCount() {
        return players.size();
    }

    public void revealAround(Player miner, Block center) {
        Sight sight = new Sight(center.getWorld(), plugin.getCompatibility());
        sight.setAir(center);
        for (int[] off : Sight.OFFSETS) {
            Block adj = center.getRelative(off[0], off[1], off[2]);
            if (plugin.getCompatibility().isOre(adj.getType())) {
                revealToPlayer(miner, adj);
                revealToViewers(adj, sight);
            }
        }
    }

    private void revealToViewers(Block block, Sight sight) {
        int distance = plugin.getConfiguration().getProximityDistance();
        double range = distance + HIDE_MARGIN;
        Location loc = block.getLocation();
        for (Entity entity : block.getWorld().getNearbyEntities(loc, range, range, range)) {
            if (!(entity instanceof Player)) continue;
            Player player = (Player) entity;
            double distanceSq = player.getLocation().distanceSquared(loc);
            if (distanceSq > range * range) continue;
            if (distance <= 0) {
                revealToPlayer(player, block);
                continue;
            }
            addHiddenOre(player, block.getX(), block.getY(), block.getZ());
            Location eye = player.getEyeLocation();
            if (distanceSq <= distance * distance
                && sight.visibility(eye.getX(), eye.getY(), eye.getZ(), block.getX(), block.getY(), block.getZ()) == Sight.VISIBLE) {
                revealToPlayer(player, block);
            }
        }
    }

    private void updateVisibility(Player player, Location loc, PlayerData data) {
        int distance = plugin.getConfiguration().getProximityDistance();
        World world = loc.getWorld();
        if (distance <= 0 || world == null) return;

        Sight sight = new Sight(world, plugin.getCompatibility());
        double eyeX = loc.getX();
        double eyeY = loc.getY() + player.getEyeHeight();
        double eyeZ = loc.getZ();
        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();
        int range = distance + HIDE_MARGIN;
        int showSq = distance * distance;
        int hideSq = range * range;

        for (int cx = (px - range) >> 4; cx <= (px + range) >> 4; cx++) {
            for (int cz = (pz - range) >> 4; cz <= (pz + range) >> 4; cz++) {
                long key = ChunkManager.chunkKey(cx, cz);
                ChunkOres chunk = data.chunks.get(key);
                if (chunk == null || chunk.hidden.isEmpty() || !world.isChunkLoaded(cx, cz)) continue;

                for (Long posObj : chunk.hidden) {
                    long pos = posObj;
                    int x = unpackX(pos);
                    int y = unpackY(pos);
                    int z = unpackZ(pos);
                    int dx = x - px;
                    int dy = y - py;
                    int dz = z - pz;
                    boolean shown = chunk.revealed.contains(pos);
                    if (dx * dx + dy * dy + dz * dz > (shown ? hideSq : showSq)) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (!plugin.getCompatibility().isOre(block.getType())) {
                        chunk.hidden.remove(pos);
                        chunk.revealed.remove(pos);
                        continue;
                    }
                    int visibility = sight.visibility(eyeX, eyeY, eyeZ, x, y, z);
                    if (visibility == Sight.VISIBLE) {
                        revealToPlayer(player, block);
                    } else if (shown) {
                        hideFromPlayer(player, block);
                    }
                    if (visibility == Sight.ENCLOSED) {
                        chunk.hidden.remove(pos);
                        chunk.revealed.remove(pos);
                    }
                }
                if (chunk.hidden.isEmpty()) {
                    data.chunks.remove(key, chunk);
                }
            }
        }
    }

    private void revealToPlayer(Player player, Block block) {
        if (isRevealed(player, block.getX(), block.getY(), block.getZ())) return;
        markRevealed(player.getUniqueId(), block.getX(), block.getY(), block.getZ());
        sendBlockUpdate(player, block);
    }

    private void hideFromPlayer(Player player, Block block) {
        unmarkRevealed(player.getUniqueId(), block.getX(), block.getY(), block.getZ());
        sendReplacementBlock(player, block);
    }

    private void sendBlockUpdate(Player player, Block block) {
        try {
            player.sendBlockChange(block.getLocation(), block.getBlockData());
            return;
        } catch (Throwable ignored) {
        }
        try {
            player.sendBlockChange(block.getLocation(), block.getType(), block.getData());
        } catch (Throwable ignored) {
        }
    }

    private void sendReplacementBlock(Player player, Block block) {
        int replacement = plugin.getCompatibility().getReplacementId(block.getType(), block.getY(), block.getWorld().getEnvironment());
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
            new Vector3i(block.getX(), block.getY(), block.getZ()),
            replacement
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private boolean isEnclosed(Block block) {
        for (int[] off : Sight.OFFSETS) {
            if (!plugin.getCompatibility().isOccluding(block.getRelative(off[0], off[1], off[2]).getType())) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        PlayerData data = players.get(event.getPlayer().getUniqueId());
        if (data == null || data.chunks.isEmpty() || event.getTo() == null) return;

        long now = System.currentTimeMillis();
        if (now - data.lastProximityCheck < CHECK_INTERVAL_MS) return;
        Location to = event.getTo();
        int version = data.version.get();
        if (version == data.lastCheckVersion && to.getWorld() == data.lastCheckWorld
            && Math.abs(to.getX() - data.lastCheckX) < 0.3 && Math.abs(to.getY() - data.lastCheckY) < 0.3
            && Math.abs(to.getZ() - data.lastCheckZ) < 0.3) {
            return;
        }
        data.lastProximityCheck = now;
        data.lastCheckVersion = version;
        data.lastCheckWorld = to.getWorld();
        data.lastCheckX = to.getX();
        data.lastCheckY = to.getY();
        data.lastCheckZ = to.getZ();

        try {
            updateVisibility(event.getPlayer(), to, data);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                plugin.getLogger().log(Level.WARNING, "Could not update hidden blocks for " + event.getPlayer().getName(), t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.getConfiguration().isWorldProtected(block.getWorld())) return;
        revealAround(event.getPlayer(), block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlock();
        if (!plugin.getConfiguration().isWorldProtected(placed.getWorld())) return;
        Player player = event.getPlayer();

        if (plugin.getCompatibility().isOre(placed.getType())) {
            revealToPlayer(player, placed);
            revealToViewers(placed, new Sight(placed.getWorld(), plugin.getCompatibility()));
            return;
        }

        if (!plugin.getCompatibility().isOccluding(placed.getType())) return;
        for (int[] off : Sight.OFFSETS) {
            Block adj = placed.getRelative(off[0], off[1], off[2]);
            if (plugin.getCompatibility().isOre(adj.getType()) && isEnclosed(adj)) {
                hideFromPlayer(player, adj);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        revealNeighbors(event.blockList(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        revealNeighbors(event.blockList(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        revealNeighbors(event.getBlocks(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        revealNeighbors(event.getBlocks(), false);
    }

    private void revealNeighbors(List<Block> blocks, boolean removed) {
        if (blocks.isEmpty()) return;
        World world = blocks.get(0).getWorld();
        if (!plugin.getConfiguration().isWorldProtected(world)) return;

        Sight sight = new Sight(world, plugin.getCompatibility());
        if (removed) {
            for (Block b : blocks) {
                sight.setAir(b);
            }
        }
        Set<Block> checked = new HashSet<Block>();
        for (Block b : blocks) {
            for (int[] off : Sight.OFFSETS) {
                Block adj = b.getRelative(off[0], off[1], off[2]);
                if (checked.add(adj) && plugin.getCompatibility().isOre(adj.getType())) {
                    revealToViewers(adj, sight);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.ensurePacketListener();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerCache(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld() != null && event.getTo() != null && event.getTo().getWorld() != null) {
            if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
                clearPlayerCache(event.getPlayer().getUniqueId());
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearPlayerCache(event.getPlayer().getUniqueId());
    }

    private static class ChunkOres {
        final Set<Long> hidden = ConcurrentHashMap.newKeySet();
        final Set<Long> revealed = ConcurrentHashMap.newKeySet();
    }

    private static class PlayerData {
        final ConcurrentMap<Long, ChunkOres> chunks = new ConcurrentHashMap<Long, ChunkOres>();
        final AtomicInteger version = new AtomicInteger();
        volatile long lastProximityCheck;
        int lastCheckVersion = -1;
        World lastCheckWorld;
        double lastCheckX, lastCheckY, lastCheckZ;
    }
}
