package com.lhawk.antixray;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkDataBulk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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

        Player player = (Player) event.getPlayer();
        if (player == null && event.getUser() != null && event.getUser().getUUID() != null) {
            player = Bukkit.getPlayer(event.getUser().getUUID());
        }
        if (player == null) return;

        if (!plugin.getConfiguration().isWorldProtected(player.getWorld().getName())) return;
        if (plugin.getConfiguration().isCheckBypass() && player.hasPermission("antixray.bypass")) return;

        try {
            if (type == PacketType.Play.Server.CHUNK_DATA) {
                WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
                if (plugin.getChunkManager().processChunk(event.getUser(), player, wrapper)) {
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.MAP_CHUNK_BULK) {
                WrapperPlayServerChunkDataBulk bulk = new WrapperPlayServerChunkDataBulk(event);
                if (plugin.getChunkManager().processChunkBulk(event.getUser(), player, bulk)) {
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
                int blockId = wrapper.getBlockId();
                if (plugin.getCompatibility().isOre(blockId)) {
                    Vector3i pos = wrapper.getBlockPosition();
                    if (!plugin.getBlockManager().isRevealed(player, pos.getX(), pos.getY(), pos.getZ())) {
                        WrappedBlockState repl = plugin.getCompatibility().getReplacementState(
                            blockId,
                            pos.getY(),
                            player.getWorld().getEnvironment(),
                            event.getUser().getClientVersion()
                        );
                        wrapper.setBlockState(repl);
                        event.markForReEncode(true);
                    }
                }
            } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
                EncodedBlock[] blocks = wrapper.getBlocks();
                if (blocks != null) {
                    boolean modified = false;
                    for (EncodedBlock b : blocks) {
                        int blockId = b.getBlockId();
                        if (plugin.getCompatibility().isOre(blockId)) {
                            if (!plugin.getBlockManager().isRevealed(player, b.getX(), b.getY(), b.getZ())) {
                                WrappedBlockState repl = plugin.getCompatibility().getReplacementState(
                                    blockId,
                                    b.getY(),
                                    player.getWorld().getEnvironment(),
                                    event.getUser().getClientVersion()
                                );
                                b.setBlockState(repl);
                                modified = true;
                            }
                        }
                    }
                    if (modified) {
                        wrapper.setBlocks(blocks);
                        event.markForReEncode(true);
                    }
                }
            }
        } catch (Throwable t) {
            if (plugin.getConfiguration().isDebug()) {
                plugin.getLogger().warning("Erro ao processar pacote " + type.getName() + ": " + t.getMessage());
            }
        }
    }
}
