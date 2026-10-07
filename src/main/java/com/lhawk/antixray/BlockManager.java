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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// Os eventos rodam na thread dona do bloco/jogador (regiao no Folia): so mexe em blocos e jogadores proximos
public class BlockManager implements Listener {

    private static final long PROXIMITY_CHECK_INTERVAL_MS = 250;
    private static final long CHECKED = Long.MIN_VALUE; // posicao pendente ja verificada

    private final AntiXray plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<UUID, PlayerData>();

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

    static int unpackX(long pos) { return (int) (pos >> 38); }
    static int unpackY(long pos) { return (int) ((pos >> 26) & 0xFFFL) - 2048; }
    static int unpackZ(long pos) { return (int) (pos << 38 >> 38); }

    private PlayerData getData(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) {
            data = new PlayerData(plugin.getConfiguration().getMaxCacheSize());
            PlayerData existing = players.putIfAbsent(uuid, data);
            if (existing != null) data = existing;
        }
        return data;
    }

    public boolean isRevealed(Player player, int x, int y, int z) {
        if (player == null) return false;
        PlayerData data = players.get(player.getUniqueId());
        return data != null && data.revealed.containsKey(pack(x, y, z));
    }

    public void markRevealed(UUID uuid, int x, int y, int z) {
        getData(uuid).revealed.put(pack(x, y, z), Boolean.TRUE);
    }

    public void unmarkRevealed(UUID uuid, int x, int y, int z) {
        PlayerData data = players.get(uuid);
        if (data != null) {
            data.revealed.remove(pack(x, y, z));
        }
    }

    // Minerios expostos que o chunk enviado escondeu (substitui os anteriores daquele chunk)
    public void setHiddenOres(Player player, int chunkX, int chunkZ, List<Long> positions) {
        long key = ChunkManager.chunkKey(chunkX, chunkZ);
        if (positions == null || positions.isEmpty()) {
            PlayerData data = players.get(player.getUniqueId());
            if (data != null) data.hiddenOres.remove(key);
            return;
        }
        long[] ores = new long[positions.size()];
        for (int i = 0; i < ores.length; i++) {
            ores[i] = positions.get(i);
        }
        getData(player.getUniqueId()).hiddenOres.put(key, ores);
    }

    // Minerio escondido num pacote de bloco: so sera revelado se estiver exposto quando o jogador chegar perto.
    // Os pacotes de um jogador sao processados em sequencia, entao copiar o array e suficiente aqui
    public void addHiddenOre(Player player, int x, int y, int z) {
        ConcurrentMap<Long, long[]> chunks = getData(player.getUniqueId()).hiddenOres;
        long key = ChunkManager.chunkKey(x >> 4, z >> 4);
        long[] old = chunks.get(key);
        long[] ores = old == null ? new long[1] : Arrays.copyOf(old, old.length + 1);
        ores[ores.length - 1] = pack(x, y, z);
        chunks.put(key, ores);
    }

    public void forgetChunk(Player player, int chunkX, int chunkZ) {
        PlayerData data = players.get(player.getUniqueId());
        if (data != null) {
            data.hiddenOres.remove(ChunkManager.chunkKey(chunkX, chunkZ));
        }
    }

    public void clearPlayerCache(UUID uuid) {
        players.remove(uuid);
    }

    public int getCachedPlayerCount() {
        return players.size();
    }

    public void revealAround(Player miner, Block center, int radius) {
        for (int[] off : OFFSETS) {
            Block adj = center.getRelative(off[0], off[1], off[2]);
            if (plugin.getCompatibility().isOre(adj.getType())) {
                revealToPlayer(miner, adj);
                revealToNearby(adj, nearbyRadius(8));
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

    // Quem esta dentro da proximity-distance tambem veria o minerio recem exposto
    private double nearbyRadius(double base) {
        return Math.max(base, plugin.getConfiguration().getProximityDistance());
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

    // Revela os minerios expostos escondidos que ficaram a menos de proximity-distance do jogador
    private void revealExposedNearby(Player player, Location loc, PlayerData data) {
        int distance = plugin.getConfiguration().getProximityDistance();
        World world = loc.getWorld();
        if (distance <= 0 || world == null) return;

        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();
        int maxDistanceSq = distance * distance;

        for (int cx = (px - distance) >> 4; cx <= (px + distance) >> 4; cx++) {
            for (int cz = (pz - distance) >> 4; cz <= (pz + distance) >> 4; cz++) {
                long key = ChunkManager.chunkKey(cx, cz);
                long[] ores = data.hiddenOres.get(key);
                if (ores == null || !world.isChunkLoaded(cx, cz)) continue;

                boolean remaining = false;
                for (int i = 0; i < ores.length; i++) {
                    long pos = ores[i];
                    if (pos == CHECKED) continue;
                    int x = unpackX(pos);
                    int y = unpackY(pos);
                    int z = unpackZ(pos);
                    int dx = x - px;
                    int dy = y - py;
                    int dz = z - pz;
                    if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                        remaining = true;
                        continue;
                    }

                    ores[i] = CHECKED;
                    // Confere no mundo: so revela se ainda for minerio e continuar exposto
                    Block block = world.getBlockAt(x, y, z);
                    if (plugin.getCompatibility().isOre(block.getType()) && !isEnclosed(block)) {
                        revealToPlayer(player, block);
                    }
                }
                if (!remaining) {
                    data.hiddenOres.remove(key, ores);
                }
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
        int replacement = plugin.getCompatibility().getReplacementId(block.getType(), block.getY(), block.getWorld().getEnvironment());
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
    public void onPlayerMove(PlayerMoveEvent event) {
        PlayerData data = players.get(event.getPlayer().getUniqueId());
        if (data == null || data.hiddenOres.isEmpty() || event.getTo() == null) return;

        long now = System.currentTimeMillis();
        if (now - data.lastProximityCheck < PROXIMITY_CHECK_INTERVAL_MS) return;
        data.lastProximityCheck = now;

        revealExposedNearby(event.getPlayer(), event.getTo(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfiguration().isEnabled()) return;
        Player player = event.getPlayer();
        if (!plugin.getConfiguration().isWorldProtected(player.getWorld())) return;
        if (plugin.getConfiguration().isCheckBypass() && player.hasPermission("antixray.bypass")) return;
        revealAround(player, event.getBlock(), plugin.getConfiguration().getRevealDistance());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfiguration().isEnabled()) return;
        Block placed = event.getBlock();
        if (!plugin.getConfiguration().isWorldProtected(placed.getWorld())) return;

        // Minerio colocado por jogador e visivel: revela para quem esta perto
        if (plugin.getCompatibility().isOre(placed.getType())) {
            revealToNearby(placed, nearbyRadius(8));
            return;
        }

        Player player = event.getPlayer();
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
        if (!plugin.getConfiguration().isWorldProtected(blocks.get(0).getWorld())) return;

        for (Block b : blocks) {
            for (int[] off : OFFSETS) {
                Block adj = b.getRelative(off[0], off[1], off[2]);
                if (plugin.getCompatibility().isOre(adj.getType())) {
                    revealToNearby(adj, nearbyRadius(radius));
                }
            }
        }
    }

    // Plugins que ligam depois do AntiXray podem trocar a instancia do PacketEvents: confere a cada entrada
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

    private static class PlayerData {
        // Posicoes ja mostradas de verdade ao jogador: nunca sao escondidas de novo
        final Map<Long, Boolean> revealed;
        // Minerios expostos escondidos, por chunk (posicoes empacotadas): revelados quando o jogador chega perto
        final ConcurrentMap<Long, long[]> hiddenOres = new ConcurrentHashMap<Long, long[]>();
        volatile long lastProximityCheck;

        PlayerData(final int maxRevealed) {
            this.revealed = Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > maxRevealed;
                }
            });
        }
    }
}
