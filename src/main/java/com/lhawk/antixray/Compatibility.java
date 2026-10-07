package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Locale;

public class Compatibility {

    private static final byte POPULATED = 1;
    private static final byte ORE = 2;
    private static final byte OCCLUDING = 4;

    private final AntiXray plugin;
    // Os pacotes de saida usam os IDs da versao do servidor; o ViaVersion converte para o cliente depois
    private final ClientVersion serverVersion;

    // Um unico array trocado por inteiro no reload: leitura sem lock e sem corrida entre tabelas
    private volatile byte[] flags = new byte[65536];

    private volatile int normalId;
    private volatile int deepslateId;
    private volatile int netherId;
    private volatile int endId;

    public Compatibility(AntiXray plugin) {
        this.plugin = plugin;
        this.serverVersion = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        reload();
    }

    public void reload() {
        Config c = plugin.getConfiguration();
        int normal = stateId(c.getNormalReplacement(), StateTypes.STONE.createBlockState(serverVersion).getGlobalId());
        this.normalId = normal;
        this.deepslateId = stateId(c.getDeepslateReplacement(), normal);
        this.netherId = stateId(c.getNetherReplacement(), normal);
        this.endId = stateId(c.getEndReplacement(), normal);
        this.flags = new byte[65536];
    }

    // Converte o bloco configurado no ID global; usa o fallback se nao existir nesta versao
    private int stateId(String name, int fallback) {
        try {
            StateType type = StateTypes.getByName(name.trim().toLowerCase(Locale.ROOT));
            int id = type != null ? type.createBlockState(serverVersion).getGlobalId() : 0;
            return id > 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public int getReplacementId(int y, World.Environment env) {
        if (env == World.Environment.NETHER) return netherId;
        if (env == World.Environment.THE_END) return endId;
        return y <= 0 ? deepslateId : normalId;
    }

    public boolean isOre(int stateId) {
        return (getFlags(stateId) & ORE) != 0;
    }

    public boolean isOccluding(int stateId) {
        return (getFlags(stateId) & OCCLUDING) != 0;
    }

    private int getFlags(int stateId) {
        if (stateId <= 0) return 0;
        byte[] table = this.flags;
        if (stateId >= table.length) return computeFlags(stateId);
        byte f = table[stateId];
        if (f == 0) {
            f = computeFlags(stateId);
            table[stateId] = f;
        }
        return f;
    }

    private byte computeFlags(int stateId) {
        byte f = POPULATED;
        try {
            StateType type = WrappedBlockState.getByGlobalId(serverVersion, stateId, false).getType();
            if (isOreType(type)) f |= ORE;
            if (isOccludingType(type)) f |= OCCLUDING;
        } catch (Throwable ignored) {
        }
        return f;
    }

    public boolean isOre(Material material) {
        return material != null && plugin.getConfiguration().getHiddenBlocks().contains(Config.normalize(material.name()));
    }

    public boolean isOccluding(Material material) {
        return material != null && material.isOccluding();
    }

    private boolean isOreType(StateType type) {
        if (type == null || type.isAir()) return false;
        return plugin.getConfiguration().getHiddenBlocks().contains(Config.normalize(type.getName()));
    }

    private boolean isOccludingType(StateType type) {
        if (type == null || type.isAir()) return false;
        String name = Config.normalize(type.getName());

        if (name.contains("air") || name.equals("water") || name.equals("lava")
            || name.contains("glass") || name.contains("leaves") || name.contains("slab")
            || name.contains("stair") || name.contains("door") || name.contains("fence")
            || name.contains("wall") || name.contains("torch") || name.contains("lantern")
            || name.contains("chest") || name.contains("carpet") || name.contains("sculk_vein")
            || name.contains("vine") || name.contains("lichen") || name.contains("rail")
            || name.contains("ice") || name.contains("pane") || name.contains("bar")
            || name.contains("chain") || name.contains("sign") || name.contains("gate")
            || name.contains("sensor") || name.contains("piston") || name.contains("bell")
            || name.contains("spawner") || name.contains("dripstone") || name.contains("amethyst")
            || name.contains("shroom") || name.contains("mushroom") || name.contains("flower")
            || name.contains("plant") || name.contains("sapling") || name.contains("candle")
            || name.contains("pot") || name.contains("hopper") || name.contains("lever")
            || name.contains("button") || name.contains("pressure_plate")) {
            return false;
        }

        if (name.contains("ore") || name.contains("stone") || name.contains("deepslate")
            || name.contains("dirt") || name.contains("gravel") || name.contains("sand")
            || name.contains("terracotta") || name.contains("concrete") || name.contains("planks")
            || name.contains("log") || name.contains("wood") || name.contains("basalt")
            || name.contains("blackstone") || name.contains("netherrack") || name.contains("end_stone")
            || name.contains("bedrock") || name.contains("obsidian") || name.contains("tuff")
            || name.contains("granite") || name.contains("diorite") || name.contains("andesite")
            || name.contains("clay") || name.contains("mud") || name.contains("prismarine")) {
            return true;
        }

        return type.isSolid() || type.isBlocking();
    }
}
