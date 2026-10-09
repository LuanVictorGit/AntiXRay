package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Compatibility {

    private static final int POPULATED = 1;
    private static final int ORE = 2;
    private static final int OCCLUDING = 4;
    private static final int REPLACEMENT_SHIFT = 8;

    private final AntiXray plugin;
    private final ClientVersion serverVersion;

    private volatile int[] info = new int[65536];
    private volatile int[] materialInfo = new int[Material.values().length];
    private volatile Map<String, Integer> explicitReplacements = Collections.emptyMap();

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
        int stone = StateTypes.STONE.createBlockState(serverVersion).getGlobalId();
        this.normalId = stone;
        this.deepslateId = stateId(StateTypes.DEEPSLATE, stone);
        this.netherId = stateId(StateTypes.NETHERRACK, stone);
        this.endId = stateId(StateTypes.END_STONE, stone);

        Map<String, Integer> explicit = new HashMap<String, Integer>();
        for (Map.Entry<String, String> e : plugin.getConfiguration().getHiddenReplacements().entrySet()) {
            int id = explicitStateId(e.getValue());
            if (id >= 0) {
                explicit.put(e.getKey(), id);
            } else {
                plugin.getLogger().warning("Unknown replacement for " + e.getKey() + ": " + e.getValue() + ", using stone instead");
            }
        }
        this.explicitReplacements = Collections.unmodifiableMap(explicit);
        this.info = new int[65536];
        this.materialInfo = new int[Material.values().length];
    }

    private int explicitStateId(String name) {
        try {
            StateType type = StateTypes.getByName(name.trim().toLowerCase(Locale.ROOT));
            if (type == null) return -1;
            if (type.isAir()) return type == StateTypes.AIR ? 0 : type.createBlockState(serverVersion).getGlobalId();
            int id = type.createBlockState(serverVersion).getGlobalId();
            return id > 0 ? id : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private int stateId(StateType type, int fallback) {
        try {
            int id = type.createBlockState(serverVersion).getGlobalId();
            return id > 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public int getReplacementId(int stateId, int y, World.Environment env) {
        int explicit = (getInfo(stateId) >>> REPLACEMENT_SHIFT) - 1;
        return explicit >= 0 ? explicit : getReplacementId(y, env);
    }

    public int getReplacementId(Material material, int y, World.Environment env) {
        Integer explicit = material == null ? null : explicitReplacements.get(Config.normalize(material.name()));
        return explicit != null ? explicit : getReplacementId(y, env);
    }

    private int getReplacementId(int y, World.Environment env) {
        if (env == World.Environment.NETHER) return netherId;
        if (env == World.Environment.THE_END) return endId;
        return y <= 0 ? deepslateId : normalId;
    }

    public boolean isOre(int stateId) {
        return (getInfo(stateId) & ORE) != 0;
    }

    public boolean isOccluding(int stateId) {
        return (getInfo(stateId) & OCCLUDING) != 0;
    }

    public boolean isOre(Material material) {
        return (getInfo(material) & ORE) != 0;
    }

    public boolean isOccluding(Material material) {
        return (getInfo(material) & OCCLUDING) != 0;
    }

    private int getInfo(int stateId) {
        if (stateId <= 0) return 0;
        int[] table = this.info;
        if (stateId >= table.length) return computeInfo(stateId);
        int value = table[stateId];
        if (value == 0) {
            value = computeInfo(stateId);
            table[stateId] = value;
        }
        return value;
    }

    private int computeInfo(int stateId) {
        int value = POPULATED;
        try {
            StateType type = WrappedBlockState.getByGlobalId(serverVersion, stateId, false).getType();
            if (isOreType(type)) {
                value |= ORE;
                Integer explicit = explicitReplacements.get(Config.normalize(type.getName()));
                if (explicit != null) value |= (explicit + 1) << REPLACEMENT_SHIFT;
            }
            if (isOccludingType(type)) value |= OCCLUDING;
        } catch (Throwable ignored) {
        }
        return value;
    }

    private int getInfo(Material material) {
        if (material == null) return 0;
        int[] table = this.materialInfo;
        int index = material.ordinal();
        if (index >= table.length) return computeInfo(material);
        int value = table[index];
        if (value == 0) {
            value = computeInfo(material);
            table[index] = value;
        }
        return value;
    }

    private int computeInfo(Material material) {
        String name = Config.normalize(material.name());
        int value = POPULATED;
        if (plugin.getConfiguration().getHiddenBlocks().contains(name)) value |= ORE;
        Boolean byName = isOccludingName(name);
        if (byName != null ? byName : material.isOccluding()) value |= OCCLUDING;
        return value;
    }

    private boolean isOreType(StateType type) {
        if (type == null || type.isAir()) return false;
        return plugin.getConfiguration().getHiddenBlocks().contains(Config.normalize(type.getName()));
    }

    private boolean isOccludingType(StateType type) {
        if (type == null || type.isAir()) return false;
        Boolean byName = isOccludingName(Config.normalize(type.getName()));
        return byName != null ? byName : type.isSolid() || type.isBlocking();
    }

    private static Boolean isOccludingName(String name) {
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
            return Boolean.FALSE;
        }

        if (name.contains("ore") || name.contains("stone") || name.contains("deepslate")
            || name.contains("dirt") || name.contains("gravel") || name.contains("sand")
            || name.contains("terracotta") || name.contains("concrete") || name.contains("planks")
            || name.contains("log") || name.contains("wood") || name.contains("basalt")
            || name.contains("blackstone") || name.contains("netherrack") || name.contains("end_stone")
            || name.contains("bedrock") || name.contains("obsidian") || name.contains("tuff")
            || name.contains("granite") || name.contains("diorite") || name.contains("andesite")
            || name.contains("clay") || name.contains("mud") || name.contains("prismarine")) {
            return Boolean.TRUE;
        }

        return null;
    }
}
