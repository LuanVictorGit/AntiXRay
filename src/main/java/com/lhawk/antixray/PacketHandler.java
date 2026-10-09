package com.lhawk.antixray;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkDataBulk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class PacketHandler extends PacketListenerAbstract {

    private final AntiXray plugin;
    private volatile boolean errorLogged;

    public PacketHandler(AntiXray plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean chunk = type == PacketType.Play.Server.CHUNK_DATA || type == PacketType.Play.Server.MAP_CHUNK_BULK;
        if (!chunk
            && type != PacketType.Play.Server.BLOCK_CHANGE
            && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE
            && type != PacketType.Play.Server.UNLOAD_CHUNK) {
            return;
        }

        try {
            Player player = findPlayer(event);
            if (player == null) return;

            if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
                WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
                plugin.getBlockManager().forgetChunk(player, wrapper.getChunkX(), wrapper.getChunkZ());
                return;
            }

            World world = player.getWorld();
            if (!plugin.getConfiguration().isWorldProtected(world)) return;

            if (type == PacketType.Play.Server.CHUNK_DATA) {
                if (plugin.getChunkManager().processChunk(player, new WrapperPlayServerChunkData(event))) {
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.MAP_CHUNK_BULK) {
                if (plugin.getChunkManager().processChunkBulk(player, new WrapperPlayServerChunkDataBulk(event))) {
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
                Vector3i pos = wrapper.getBlockPosition();
                if (shouldHide(player, wrapper.getBlockId(), pos.getX(), pos.getY(), pos.getZ())) {
                    wrapper.setBlockID(hide(player, world, wrapper.getBlockId(), pos.getX(), pos.getY(), pos.getZ()));
                    event.markForReEncode(true);
                }
            } else {
                WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
                EncodedBlock[] blocks = wrapper.getBlocks();
                if (blocks == null) return;
                boolean modified = false;
                for (EncodedBlock b : blocks) {
                    if (shouldHide(player, b.getBlockId(), b.getX(), b.getY(), b.getZ())) {
                        b.setBlockId(hide(player, world, b.getBlockId(), b.getX(), b.getY(), b.getZ()));
                        modified = true;
                    }
                }
                if (modified) {
                    event.markForReEncode(true);
                }
            }
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                plugin.getLogger().log(Level.WARNING, "Could not process packet " + type.getName(), t);
            }
        }
    }

    private static Player findPlayer(PacketSendEvent event) {
        Object platformPlayer = event.getPlayer();
        if (platformPlayer instanceof Player) return (Player) platformPlayer;

        User user = event.getUser();
        if (user == null) return null;
        Player player = user.getUUID() != null ? Bukkit.getPlayer(user.getUUID()) : null;
        if (player == null && user.getName() != null) {
            player = Bukkit.getPlayerExact(user.getName());
        }
        return player;
    }

    private boolean shouldHide(Player player, int blockId, int x, int y, int z) {
        if (!plugin.getCompatibility().isOre(blockId)) {
            plugin.getBlockManager().unmarkRevealed(player.getUniqueId(), x, y, z);
            return false;
        }
        return !plugin.getBlockManager().isRevealed(player, x, y, z);
    }

    private int hide(Player player, World world, int blockId, int x, int y, int z) {
        if (plugin.getConfiguration().getProximityDistance() > 0) {
            plugin.getBlockManager().addHiddenOre(player, x, y, z);
        }
        return plugin.getCompatibility().getReplacementId(blockId, y, world.getEnvironment());
    }
}
