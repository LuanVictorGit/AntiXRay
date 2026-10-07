package com.lhawk.antixray;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkDataBulk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

// Roda nas threads do Netty: so usa os dados do pacote e caches thread-safe, nunca le blocos do mundo (seguro no Folia)
public class PacketHandler extends PacketListenerAbstract {

    private final AntiXray plugin;

    public PacketHandler(AntiXray plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!plugin.getConfiguration().isEnabled()) return;

        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.CHUNK_DATA
            && type != PacketType.Play.Server.MAP_CHUNK_BULK
            && type != PacketType.Play.Server.BLOCK_CHANGE
            && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null && event.getUser() != null && event.getUser().getUUID() != null) {
            player = Bukkit.getPlayer(event.getUser().getUUID());
        }
        if (player == null) return;

        World world = player.getWorld();
        if (!plugin.getConfiguration().isWorldProtected(world.getName())) return;
        if (plugin.getConfiguration().isCheckBypass() && player.hasPermission("antixray.bypass")) return;

        try {
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
                    wrapper.setBlockID(plugin.getCompatibility().getReplacementId(pos.getY(), world.getEnvironment()));
                    event.markForReEncode(true);
                }
            } else {
                WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
                EncodedBlock[] blocks = wrapper.getBlocks();
                if (blocks == null) return;
                boolean modified = false;
                for (EncodedBlock b : blocks) {
                    if (shouldHide(player, b.getBlockId(), b.getX(), b.getY(), b.getZ())) {
                        b.setBlockId(plugin.getCompatibility().getReplacementId(b.getY(), world.getEnvironment()));
                        modified = true;
                    }
                }
                if (modified) {
                    event.markForReEncode(true);
                }
            }
        } catch (Throwable t) {
            if (plugin.getConfiguration().isDebug()) {
                plugin.getLogger().warning("Erro ao processar pacote " + type.getName() + ": " + t.getMessage());
            }
        }
    }

    private boolean shouldHide(Player player, int blockId, int x, int y, int z) {
        return plugin.getCompatibility().isOre(blockId) && !plugin.getBlockManager().isRevealed(player, x, y, z);
    }
}
