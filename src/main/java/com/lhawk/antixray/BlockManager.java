package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Os eventos rodam na thread dona do bloco (regiao no Folia): so mexe em blocos e jogadores proximos
public class BlockManager implements Listener {

    private final AntiXray plugin;
    private final Map<UUID, Map<Long, Boolean>> playerCaches = new ConcurrentHashMap<UUID, Map<Long, Boolean>>();

    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    public BlockManager(AntiXray plugin) {
        this.plugin = plugin;
    }

    public static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) (y + 2048) & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL);
    }

    public boolean isRevealed(Player player, int x, int y, int z) {
        if (player == null) return false;
        Map<Long, Boolean> map = playerCaches.get(player.getUniqueId());
        return map != null && map.containsKey(pack(x, y, z));
    }

    public void markRevealed(UUID uuid, int x, int y, int z) {
        Map<Long, Boolean> map = playerCaches.get(uuid);
        if (map == null) {
            final int max = plugin.getConfiguration().getMaxCacheSize();
            map = Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > max;
                }
            });
            Map<Long, Boolean> existing = playerCaches.putIfAbsent(uuid, map);
            if (existing != null) map = existing;
        }
        map.put(pack(x, y, z), Boolean.TRUE);
    }

    public void unmarkRevealed(UUID uuid, int x, int y, int z) {
        Map<Long, Boolean> map = playerCaches.get(uuid);
        if (map != null) {
            map.remove(pack(x, y, z));
        }
    }

    public void clearPlayerCache(UUID uuid) {
        playerCaches.remove(uuid);
    }

    public int getCachedPlayerCount() {
        return playerCaches.size();
    }

    public void revealAround(Player miner, Block center, int radius) {
        for (int[] off : OFFSETS) {
            Block adj = center.getRelative(off[0], off[1], off[2]);
            if (plugin.getCompatibility().isOre(adj.getType())) {
                revealToPlayer(miner, adj);
                revealToNearby(adj, 8);
            }
        }

        if (radius > 1) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > radius) continue;
                        Block b = center.getRelative(dx, dy, dz);
                        if (plugin.getCompatibility().isOre(b.getType())) {
                            revealToPlayer(miner, b);
                        }
                    }
                }
            }
        }
    }

    // getNearbyEntities so enxerga a regiao atual, entao e seguro no Folia (world.getPlayers() pegaria outras regioes)
    private void revealToNearby(Block ore, double radius) {
        Location loc = ore.getLocation();
        double maxDistanceSq = radius * radius;
        for (Entity entity : ore.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity instanceof Player && entity.getLocation().distanceSquared(loc) <= maxDistanceSq) {
                revealToPlayer((Player) entity, ore);
            }
        }
    }

    private void revealToPlayer(Player player, Block ore) {
        if (isRevealed(player, ore.getX(), ore.getY(), ore.getZ())) return;
        markRevealed(player.getUniqueId(), ore.getX(), ore.getY(), ore.getZ());
        sendBlockUpdate(player, ore);
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
        int replacement = plugin.getCompatibility().getReplacementId(block.getY(), block.getWorld().getEnvironment());
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
            new Vector3i(block.getX(), block.getY(), block.getZ()),
            replacement
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private boolean isEnclosed(Block block) {
        for (int[] off : OFFSETS) {
            if (!plugin.getCompatibility().isOccluding(block.getRelative(off[0], off[1], off[2]).getType())) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfiguration().isEnabled()) return;
        Player player = event.getPlayer();
        if (!plugin.getConfiguration().isWorldProtected(player.getWorld().getName())) return;
        if (plugin.getConfiguration().isCheckBypass() && player.hasPermission("antixray.bypass")) return;
        revealAround(player, event.getBlock(), plugin.getConfiguration().getRevealDistance());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfiguration().isEnabled()) return;
        Block placed = event.getBlock();
        Player player = event.getPlayer();
        if (!plugin.getConfiguration().isWorldProtected(placed.getWorld().getName())) return;
        if (plugin.getConfiguration().isCheckBypass() && player.hasPermission("antixray.bypass")) return;
        if (!plugin.getCompatibility().isOccluding(placed.getType())) return;

        for (int[] off : OFFSETS) {
            Block adj = placed.getRelative(off[0], off[1], off[2]);
            if (plugin.getCompatibility().isOre(adj.getType()) && isEnclosed(adj)) {
                unmarkRevealed(player.getUniqueId(), adj.getX(), adj.getY(), adj.getZ());
                sendReplacementBlock(player, adj);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        revealNeighbors(event.blockList(), 16);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        revealNeighbors(event.blockList(), 16);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        revealNeighbors(event.getBlocks(), 12);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        revealNeighbors(event.getBlocks(), 12);
    }

    // Blocos removidos/movidos podem expor minerios vizinhos: revela para quem estiver perto
    private void revealNeighbors(List<Block> blocks, double radius) {
        if (!plugin.getConfiguration().isEnabled() || blocks.isEmpty()) return;
        if (!plugin.getConfiguration().isWorldProtected(blocks.get(0).getWorld().getName())) return;

        for (Block b : blocks) {
            for (int[] off : OFFSETS) {
                Block adj = b.getRelative(off[0], off[1], off[2]);
                if (plugin.getCompatibility().isOre(adj.getType())) {
                    revealToNearby(adj, radius);
                }
            }
        }
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
}
