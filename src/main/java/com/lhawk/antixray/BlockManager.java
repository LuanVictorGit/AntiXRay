package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
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
        if (map == null) return false;
        return map.containsKey(pack(x, y, z));
    }

    public void markRevealed(UUID uuid, int x, int y, int z) {
        Map<Long, Boolean> map = playerCaches.get(uuid);
        if (map == null) {
            final int max = plugin.getConfiguration().getMaxCacheSize();
            map = Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(max, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > max;
                }
            });
            playerCaches.put(uuid, map);
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

    public void removePlayer(UUID uuid) {
        playerCaches.remove(uuid);
    }

    public int getCachedPlayerCount() {
        return playerCaches.size();
    }

    public void revealAround(Player miner, Block center, int radius) {
        World world = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        for (int[] off : OFFSETS) {
            Block adj = world.getBlockAt(cx + off[0], cy + off[1], cz + off[2]);
            if (plugin.getCompatibility().isOre(adj.getType())) {
                revealToPlayer(miner, adj);
                for (Player nearby : world.getPlayers()) {
                    if (nearby.equals(miner)) continue;
                    if (nearby.getLocation().distanceSquared(adj.getLocation()) <= 64.0) {
                        revealToPlayer(nearby, adj);
                    }
                }
            }
        }

        if (radius > 1) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > radius) continue;
                        Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (plugin.getCompatibility().isOre(b.getType())) {
                            revealToPlayer(miner, b);
                        }
                    }
                }
            }
        }
    }

    private void revealToPlayer(Player player, Block oreBlock) {
        markRevealed(player.getUniqueId(), oreBlock.getX(), oreBlock.getY(), oreBlock.getZ());
        sendBlockUpdate(player, oreBlock);
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
        WrappedBlockState repl = plugin.getCompatibility().getReplacementState(
            0,
            block.getY(),
            block.getWorld().getEnvironment(),
            PacketEvents.getAPI().getPlayerManager().getClientVersion(player)
        );
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
            new Vector3i(block.getX(), block.getY(), block.getZ()),
            repl.getGlobalId()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
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
        if (!plugin.getCompatibility().isOccluding(placed.getType())) return;

        World world = placed.getWorld();
        int px = placed.getX();
        int py = placed.getY();
        int pz = placed.getZ();

        for (int[] off : OFFSETS) {
            Block adj = world.getBlockAt(px + off[0], py + off[1], pz + off[2]);
            if (plugin.getCompatibility().isOre(adj.getType())) {
                boolean allOccluded = true;
                for (int[] off2 : OFFSETS) {
                    Block n = world.getBlockAt(adj.getX() + off2[0], adj.getY() + off2[1], adj.getZ() + off2[2]);
                    if (!plugin.getCompatibility().isOccluding(n.getType())) {
                        allOccluded = false;
                        break;
                    }
                }
                if (allOccluded) {
                    unmarkRevealed(player.getUniqueId(), adj.getX(), adj.getY(), adj.getZ());
                    sendReplacementBlock(player, adj);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.getLocation(), event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.getBlock().getLocation(), event.blockList());
    }

    private void handleExplosion(Location loc, List<Block> blocks) {
        if (!plugin.getConfiguration().isEnabled()) return;
        if (loc.getWorld() == null || !plugin.getConfiguration().isWorldProtected(loc.getWorld().getName())) return;

        World world = loc.getWorld();
        for (Block b : blocks) {
            int bx = b.getX();
            int by = b.getY();
            int bz = b.getZ();
            for (int[] off : OFFSETS) {
                Block adj = world.getBlockAt(bx + off[0], by + off[1], bz + off[2]);
                if (plugin.getCompatibility().isOre(adj.getType())) {
                    for (Player p : world.getPlayers()) {
                        if (p.getLocation().distanceSquared(adj.getLocation()) <= 256.0) {
                            revealToPlayer(p, adj);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlock(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlock(), event.getBlocks());
    }

    private void handlePiston(Block piston, List<Block> blocks) {
        if (!plugin.getConfiguration().isEnabled()) return;
        World world = piston.getWorld();
        if (!plugin.getConfiguration().isWorldProtected(world.getName())) return;

        for (Block b : blocks) {
            int bx = b.getX();
            int by = b.getY();
            int bz = b.getZ();
            for (int[] off : OFFSETS) {
                Block adj = world.getBlockAt(bx + off[0], by + off[1], bz + off[2]);
                if (plugin.getCompatibility().isOre(adj.getType())) {
                    for (Player p : world.getPlayers()) {
                        if (p.getLocation().distanceSquared(adj.getLocation()) <= 144.0) {
                            revealToPlayer(p, adj);
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
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
