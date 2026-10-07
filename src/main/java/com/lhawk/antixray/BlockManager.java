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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

// Os eventos rodam na thread dona do bloco/jogador (regiao no Folia): so mexe em blocos e jogadores proximos.
// Blocos escondidos expostos (cavernas, agua, lava) so aparecem enquanto estao na linha de visao do jogador:
// nada e visto atraves das paredes, nem com textura ou hack de x-ray
public class BlockManager implements Listener {

    private static final long CHECK_INTERVAL_MS = 250;
    private static final long CHECKED = Long.MIN_VALUE; // posicao que nao precisa mais ser vigiada
    // Blocos ja mostrados continuam sendo conferidos ate esta distancia alem da proximity-distance:
    // quem se afasta olhando para o bloco nao o ve sumir, mas ele some quando sai da visao
    private static final int HIDE_MARGIN = 16;

    private static final int ENCLOSED = 0;
    private static final int NOT_VISIBLE = 1;
    private static final int VISIBLE = 2;

    // Pontos de cada face mirados pelos raios: o centro e perto dos 4 cantos
    private static final double[][] FACE_POINTS = {{0, 0}, {-0.45, -0.45}, {-0.45, 0.45}, {0.45, -0.45}, {0.45, 0.45}};

    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

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
        return data != null && data.isRevealed(ChunkManager.chunkKey(x >> 4, z >> 4), pack(x, y, z));
    }

    public void markRevealed(UUID uuid, int x, int y, int z) {
        PlayerData data = getData(uuid);
        long key = ChunkManager.chunkKey(x >> 4, z >> 4);
        Set<Long> chunk = data.revealed.get(key);
        if (chunk == null) {
            chunk = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
            Set<Long> existing = data.revealed.putIfAbsent(key, chunk);
            if (existing != null) chunk = existing;
        }
        chunk.add(pack(x, y, z));
    }

    public void unmarkRevealed(UUID uuid, int x, int y, int z) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        Set<Long> chunk = data.revealed.get(ChunkManager.chunkKey(x >> 4, z >> 4));
        if (chunk != null) {
            chunk.remove(pack(x, y, z));
        }
    }

    // Chunk enviado: guarda os blocos escondidos expostos dele (substitui os anteriores). O que o
    // jogador via nesse chunk deixa de valer: chegou tudo de novo, com os blocos escondidos
    public void setHiddenOres(Player player, int chunkX, int chunkZ, List<Long> positions) {
        long key = ChunkManager.chunkKey(chunkX, chunkZ);
        if (positions == null || positions.isEmpty()) {
            forgetChunk(player, chunkX, chunkZ);
            return;
        }
        long[] ores = new long[positions.size()];
        for (int i = 0; i < ores.length; i++) {
            ores[i] = positions.get(i);
        }
        PlayerData data = getData(player.getUniqueId());
        synchronized (data) {
            data.hiddenOres.put(key, ores);
            data.revealed.remove(key);
            data.version++;
        }
    }

    // Passa a vigiar um bloco escondido: ele aparece enquanto estiver na linha de visao do jogador
    public void addHiddenOre(Player player, int x, int y, int z) {
        PlayerData data = getData(player.getUniqueId());
        long key = ChunkManager.chunkKey(x >> 4, z >> 4);
        long pos = pack(x, y, z);
        synchronized (data) {
            long[] old = data.hiddenOres.get(key);
            if (old != null) {
                for (long existing : old) {
                    if (existing == pos) return;
                }
            }
            long[] ores = old == null ? new long[1] : Arrays.copyOf(old, old.length + 1);
            ores[ores.length - 1] = pos;
            data.hiddenOres.put(key, ores);
            data.version++;
        }
    }

    public void forgetChunk(Player player, int chunkX, int chunkZ) {
        PlayerData data = players.get(player.getUniqueId());
        if (data != null) {
            long key = ChunkManager.chunkKey(chunkX, chunkZ);
            data.hiddenOres.remove(key);
            data.revealed.remove(key);
        }
    }

    public void clearPlayerCache(UUID uuid) {
        players.remove(uuid);
    }

    public int getCachedPlayerCount() {
        return players.size();
    }

    // Mesma regra do PacketHandler: so quem recebe os chunks com os blocos escondidos
    private boolean isProtected(Player player) {
        Config config = plugin.getConfiguration();
        return config.isEnabled() && config.isWorldProtected(player.getWorld())
            && !(config.isCheckBypass() && player.hasPermission("antixray.bypass"));
    }

    // Bloco quebrado: os blocos escondidos vizinhos ficam expostos. Quem quebrou ve na hora;
    // os outros jogadores quando estiverem na linha de visao
    public void revealAround(Player miner, Block center) {
        Sight sight = new Sight(center.getWorld());
        sight.setAir(center); // durante o evento o bloco ainda esta no mundo
        for (int[] off : OFFSETS) {
            Block adj = center.getRelative(off[0], off[1], off[2]);
            if (plugin.getCompatibility().isOre(adj.getType())) {
                revealToPlayer(miner, adj);
                revealToViewers(adj, sight);
            }
        }
    }

    // Jogadores perto passam a vigiar o bloco recem exposto; quem ja o ve recebe na hora.
    // getNearbyEntities so enxerga a regiao atual, entao e seguro no Folia (world.getPlayers() pegaria outras regioes)
    private void revealToViewers(Block block, Sight sight) {
        int distance = plugin.getConfiguration().getProximityDistance();
        double range = distance + HIDE_MARGIN;
        Location loc = block.getLocation();
        for (Entity entity : block.getWorld().getNearbyEntities(loc, range, range, range)) {
            if (!(entity instanceof Player)) continue;
            Player player = (Player) entity;
            double distanceSq = player.getLocation().distanceSquared(loc);
            if (distanceSq > range * range || !isProtected(player)) continue;
            if (distance <= 0) {
                revealToPlayer(player, block); // proximity-distance 0: expostos sempre visiveis
                continue;
            }
            addHiddenOre(player, block.getX(), block.getY(), block.getZ());
            Location eye = player.getEyeLocation();
            if (distanceSq <= distance * distance
                && sight.visibility(eye.getX(), eye.getY(), eye.getZ(), block.getX(), block.getY(), block.getZ()) == VISIBLE) {
                revealToPlayer(player, block);
            }
        }
    }

    // Mostra os blocos vigiados que o jogador ve agora e esconde de novo os que sairam da visao dele
    private void updateVisibility(Player player, Location loc, PlayerData data) {
        int distance = plugin.getConfiguration().getProximityDistance();
        World world = loc.getWorld();
        if (distance <= 0 || world == null) return;

        Sight sight = new Sight(world);
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
                    boolean shown = data.isRevealed(key, pos);
                    // Longe: fica como esta (quem se afasta olhando nao ve o bloco sumir)
                    if (dx * dx + dy * dy + dz * dz > (shown ? hideSq : showSq)) {
                        remaining = true;
                        continue;
                    }

                    // Minerado ou trocado: o servidor ja mandou o bloco novo, nao precisa mais vigiar
                    Block block = world.getBlockAt(x, y, z);
                    if (!plugin.getCompatibility().isOre(block.getType())) {
                        ores[i] = CHECKED;
                        continue;
                    }
                    int visibility = sight.visibility(eyeX, eyeY, eyeZ, x, y, z);
                    if (visibility == VISIBLE) {
                        revealToPlayer(player, block);
                    } else if (shown) {
                        hideFromPlayer(player, block);
                    }
                    // Cercado: sai da lista; volta se um vizinho for quebrado
                    if (visibility == ENCLOSED) {
                        ores[i] = CHECKED;
                    } else {
                        remaining = true;
                    }
                }
                if (!remaining) {
                    data.hiddenOres.remove(key, ores);
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
        if (!plugin.getConfiguration().isEnabled()) return;

        long now = System.currentTimeMillis();
        if (now - data.lastProximityCheck < CHECK_INTERVAL_MS) return;
        Location to = event.getTo();
        int version = data.version;
        if (version == data.lastCheckVersion && to.getWorld() == data.lastCheckWorld
            && Math.abs(to.getX() - data.lastCheckX) < 0.3 && Math.abs(to.getY() - data.lastCheckY) < 0.3
            && Math.abs(to.getZ() - data.lastCheckZ) < 0.3) {
            return; // parado (so girando a cabeca) e nada novo vigiado: a visao nao mudou
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
            // O primeiro erro sempre aparece no console; os seguintes so com debug ativo
            if (!errorLogged || plugin.getConfiguration().isDebug()) {
                errorLogged = true;
                plugin.getLogger().log(Level.WARNING, "Erro ao conferir a linha de visao (os proximos so aparecem com debug ativo)", t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.getConfiguration().isEnabled() || !plugin.getConfiguration().isWorldProtected(block.getWorld())) return;
        revealAround(event.getPlayer(), block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlock();
        if (!plugin.getConfiguration().isEnabled() || !plugin.getConfiguration().isWorldProtected(placed.getWorld())) return;
        Player player = event.getPlayer();

        // Bloco escondido colocado por jogador: quem colocou ve; os outros quando estiver na visao deles
        if (plugin.getCompatibility().isOre(placed.getType())) {
            revealToPlayer(player, placed);
            revealToViewers(placed, new Sight(placed.getWorld()));
            return;
        }

        // Bloco opaco colocado pode cercar um bloco escondido: esconde de novo para quem colocou
        if (!isProtected(player) || !plugin.getCompatibility().isOccluding(placed.getType())) return;
        for (int[] off : OFFSETS) {
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

    // Blocos removidos/movidos podem expor blocos escondidos vizinhos: quem estiver perto passa a vigia-los
    private void revealNeighbors(List<Block> blocks, boolean removed) {
        if (!plugin.getConfiguration().isEnabled() || blocks.isEmpty()) return;
        World world = blocks.get(0).getWorld();
        if (!plugin.getConfiguration().isWorldProtected(world)) return;

        Sight sight = new Sight(world);
        if (removed) {
            for (Block b : blocks) {
                sight.setAir(b); // explodidos ainda estao no mundo durante o evento
            }
        }
        for (Block b : blocks) {
            for (int[] off : OFFSETS) {
                Block adj = b.getRelative(off[0], off[1], off[2]);
                if (plugin.getCompatibility().isOre(adj.getType())) {
                    revealToViewers(adj, sight);
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

    // Linha de visao pelos blocos do mundo. Guarda o que ja leu: os raios passam pelos mesmos blocos
    private final class Sight {
        private final World world;
        private final Map<Long, Boolean> occluding = new HashMap<Long, Boolean>();

        Sight(World world) {
            this.world = world;
        }

        // Bloco que ainda esta no mundo durante o evento, mas vai sumir (quebrado ou explodido)
        void setAir(Block block) {
            occluding.put(pack(block.getX(), block.getY(), block.getZ()), Boolean.FALSE);
        }

        // Uma face do bloco e vista se o vizinho dela nao e opaco, se ela esta virada para o olho e se
        // algum raio do olho ate ela nao cruza bloco opaco. Sem nenhuma face livre o bloco esta cercado
        int visibility(double eyeX, double eyeY, double eyeZ, int x, int y, int z) {
            int result = ENCLOSED;
            for (int[] off : OFFSETS) {
                int nx = x + off[0];
                int ny = y + off[1];
                int nz = z + off[2];
                if (isOccluding(nx, ny, nz)) continue;
                result = NOT_VISIBLE;
                // Olho atras do plano da face: ela esta virada para o outro lado
                if ((eyeX - x - 0.5) * off[0] + (eyeY - y - 0.5) * off[1] + (eyeZ - z - 0.5) * off[2] <= 0.5) continue;

                // Mira um pouco para dentro do vizinho, colado na face: basta o raio chegar nele
                double cx = nx + 0.5 - off[0] * 0.49;
                double cy = ny + 0.5 - off[1] * 0.49;
                double cz = nz + 0.5 - off[2] * 0.49;
                for (double[] p : FACE_POINTS) {
                    double tx = off[0] == 0 ? cx + p[0] : cx;
                    double ty = off[1] == 0 ? cy + p[off[0] == 0 ? 1 : 0] : cy;
                    double tz = off[2] == 0 ? cz + p[1] : cz;
                    if (rayClear(eyeX, eyeY, eyeZ, tx, ty, tz, nx, ny, nz)) return VISIBLE;
                }
            }
            return result;
        }

        // Percorre os blocos cruzados pelo raio (Amanatides-Woo): true se chegar ao bloco alvo sem cruzar bloco opaco
        private boolean rayClear(double ox, double oy, double oz, double tx, double ty, double tz,
                                 int targetX, int targetY, int targetZ) {
            int x = (int) Math.floor(ox);
            int y = (int) Math.floor(oy);
            int z = (int) Math.floor(oz);
            if (x == targetX && y == targetY && z == targetZ) return true;

            double dx = tx - ox;
            double dy = ty - oy;
            double dz = tz - oz;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            int stepX = dx > 0 ? 1 : -1;
            int stepY = dy > 0 ? 1 : -1;
            int stepZ = dz > 0 ? 1 : -1;
            double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
            double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
            double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
            double maxX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - ox : ox - x) * deltaX;
            double maxY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - oy : oy - y) * deltaY;
            double maxZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - oz : oz - z) * deltaZ;

            while (true) {
                if (maxX < maxY && maxX < maxZ) {
                    if (maxX > length) return true;
                    x += stepX;
                    maxX += deltaX;
                } else if (maxY < maxZ) {
                    if (maxY > length) return true;
                    y += stepY;
                    maxY += deltaY;
                } else {
                    if (maxZ > length) return true;
                    z += stepZ;
                    maxZ += deltaZ;
                }
                if (x == targetX && y == targetY && z == targetZ) return true;
                if (isOccluding(x, y, z)) return false;
            }
        }

        boolean isOccluding(int x, int y, int z) {
            long key = pack(x, y, z);
            Boolean value = occluding.get(key);
            if (value == null) {
                // Chunk nao carregado conta como parede: nunca carrega chunk so para olhar
                value = !world.isChunkLoaded(x >> 4, z >> 4)
                    || plugin.getCompatibility().isOccluding(world.getBlockAt(x, y, z).getType());
                occluding.put(key, value);
            }
            return value;
        }
    }

    private static class PlayerData {
        // Posicoes que o jogador ve de verdade agora, por chunk; as outras ele recebe escondidas
        final ConcurrentMap<Long, Set<Long>> revealed = new ConcurrentHashMap<Long, Set<Long>>();
        // Blocos escondidos expostos vigiados, por chunk (posicoes empacotadas): aparecem e somem conforme a visao
        final ConcurrentMap<Long, long[]> hiddenOres = new ConcurrentHashMap<Long, long[]>();
        volatile long lastProximityCheck;
        // Muda a cada bloco vigiado novo; com a ultima posicao checada evita refazer a checagem parado
        volatile int version;
        int lastCheckVersion = -1;
        World lastCheckWorld;
        double lastCheckX, lastCheckY, lastCheckZ;

        boolean isRevealed(long chunkKey, long pos) {
            Set<Long> chunk = revealed.get(chunkKey);
            return chunk != null && chunk.contains(pos);
        }
    }
}
