package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Compatibility {

    private final AntiXray plugin;
    private volatile boolean[] oreTable;
    private volatile boolean[] occludingTable;
    private volatile boolean[] populated;

    private final Map<ClientVersion, WrappedBlockState> stoneCache = new ConcurrentHashMap<ClientVersion, WrappedBlockState>();
    private final Map<ClientVersion, WrappedBlockState> deepslateCache = new ConcurrentHashMap<ClientVersion, WrappedBlockState>();
    private final Map<ClientVersion, WrappedBlockState> netherrackCache = new ConcurrentHashMap<ClientVersion, WrappedBlockState>();
    private final Map<ClientVersion, WrappedBlockState> endStoneCache = new ConcurrentHashMap<ClientVersion, WrappedBlockState>();

    private ClientVersion serverVersion;

    public Compatibility(AntiXray plugin) {
        this.plugin = plugin;
        this.oreTable = new boolean[65536];
        this.occludingTable = new boolean[65536];
        this.populated = new boolean[65536];
        try {
            this.serverVersion = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        } catch (Throwable t) {
            this.serverVersion = ClientVersion.getLatest();
        }
    }

    private synchronized void ensureCapacity(int stateId) {
        if (stateId >= populated.length) {
            int newCap = Math.max(stateId + 8192, populated.length * 2);
            this.oreTable = Arrays.copyOf(this.oreTable, newCap);
            this.occludingTable = Arrays.copyOf(this.occludingTable, newCap);
            this.populated = Arrays.copyOf(this.populated, newCap);
        }
    }

    private synchronized void populate(int stateId) {
        ensureCapacity(stateId);
        if (populated[stateId]) return;
        try {
            ClientVersion ver = this.serverVersion != null ? this.serverVersion : ClientVersion.getLatest();
            WrappedBlockState state = WrappedBlockState.getByGlobalId(ver, stateId, false);
            if (state != null) {
                StateType type = state.getType();
                if (type != null) {
                    oreTable[stateId] = isOreType(type);
                    occludingTable[stateId] = isOccludingType(type);
                }
            }
        } catch (Throwable ignored) {
        }
        populated[stateId] = true;
    }

    public boolean isOre(int stateId) {
        if (stateId <= 0) return false;
        if (stateId >= populated.length || !populated[stateId]) {
            populate(stateId);
        }
        return oreTable[stateId];
    }

    public boolean isOccluding(int stateId) {
        if (stateId <= 0) return false;
        if (stateId >= populated.length || !populated[stateId]) {
            populate(stateId);
        }
        return occludingTable[stateId];
    }

    public boolean isOre(Material material) {
        if (material == null) return false;
        String name = material.name().toLowerCase();
        if (name.startsWith("minecraft:")) name = name.substring(10);
        return plugin.getConfiguration().getHiddenBlocks().contains(name);
    }

    public boolean isOccluding(Material material) {
        if (material == null) return false;
        try {
            return material.isOccluding();
        } catch (Throwable t) {
            return material.isSolid();
        }
    }

    private boolean isOreType(StateType type) {
        if (type == null || type.isAir()) return false;
        String name = type.getName();
        if (name == null) return false;
        name = name.toLowerCase();
        if (name.startsWith("minecraft:")) name = name.substring(10);
        return plugin.getConfiguration().getHiddenBlocks().contains(name);
    }

    private boolean isOccludingType(StateType type) {
        if (type == null || type.isAir()) return false;
        String name = type.getName();
        if (name == null) return false;
        name = name.toLowerCase();
        if (name.startsWith("minecraft:")) name = name.substring(10);

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

    public WrappedBlockState getReplacementState(int originalStateId, int y, World.Environment env, ClientVersion version) {
        if (env == World.Environment.NETHER) {
            return getNetherrackState(version);
        }
        if (env == World.Environment.THE_END) {
            return getEndStoneState(version);
        }
        if (y <= 0) {
            return getDeepslateState(version);
        }
        return getStoneState(version);
    }

    public WrappedBlockState getStoneState(ClientVersion version) {
        ClientVersion key = version != null ? version : ClientVersion.getLatest();
        WrappedBlockState state = stoneCache.get(key);
        if (state == null) {
            try {
                state = StateTypes.STONE.createBlockState(key);
            } catch (Throwable t) {
                state = WrappedBlockState.getByGlobalId(key, 1, false);
            }
            if (state != null) stoneCache.put(key, state);
        }
        return state;
    }

    public WrappedBlockState getDeepslateState(ClientVersion version) {
        ClientVersion key = version != null ? version : ClientVersion.getLatest();
        WrappedBlockState state = deepslateCache.get(key);
        if (state == null) {
            try {
                if (StateTypes.DEEPSLATE != null && key.isNewerThanOrEquals(ClientVersion.V_1_18)) {
                    state = StateTypes.DEEPSLATE.createBlockState(key);
                }
            } catch (Throwable ignored) {
            }
            if (state == null) {
                state = getStoneState(key);
            }
            deepslateCache.put(key, state);
        }
        return state;
    }

    public WrappedBlockState getNetherrackState(ClientVersion version) {
        ClientVersion key = version != null ? version : ClientVersion.getLatest();
        WrappedBlockState state = netherrackCache.get(key);
        if (state == null) {
            try {
                state = StateTypes.NETHERRACK.createBlockState(key);
            } catch (Throwable t) {
                state = WrappedBlockState.getByGlobalId(key, 87, false);
            }
            if (state != null) netherrackCache.put(key, state);
        }
        return state;
    }

    public WrappedBlockState getEndStoneState(ClientVersion version) {
        ClientVersion key = version != null ? version : ClientVersion.getLatest();
        WrappedBlockState state = endStoneCache.get(key);
        if (state == null) {
            try {
                state = StateTypes.END_STONE.createBlockState(key);
            } catch (Throwable t) {
                state = getStoneState(key);
            }
            if (state != null) endStoneCache.put(key, state);
        }
        return state;
    }

    public synchronized void reload() {
        this.oreTable = new boolean[65536];
        this.occludingTable = new boolean[65536];
        this.populated = new boolean[65536];
        this.stoneCache.clear();
        this.deepslateCache.clear();
        this.netherrackCache.clear();
        this.endStoneCache.clear();
        try {
            this.serverVersion = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        } catch (Throwable t) {
            this.serverVersion = ClientVersion.getLatest();
        }
    }
}
