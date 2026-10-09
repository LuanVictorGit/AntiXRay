package com.lhawk.antixray;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Config {

    private final AntiXray plugin;
    private final Map<UUID, Boolean> protectedWorlds = new ConcurrentHashMap<UUID, Boolean>();
    private volatile Set<String> worlds = Collections.emptySet();
    private volatile Set<String> hiddenBlocks = Collections.emptySet();
    private volatile Map<String, String> hiddenReplacements = Collections.emptyMap();
    private volatile int proximityDistance;

    public Config(AntiXray plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        Set<String> newWorlds = new HashSet<String>();
        for (String w : c.getStringList("worlds")) {
            newWorlds.add(w.toLowerCase(Locale.ROOT));
        }
        this.worlds = Collections.unmodifiableSet(newWorlds);
        this.protectedWorlds.clear();

        Set<String> newHiddenBlocks = new HashSet<String>();
        Map<String, String> newReplacements = new HashMap<String, String>();
        for (String entry : c.getStringList("hidden-blocks")) {
            int separator = entry.indexOf('=');
            String block = normalize(separator >= 0 ? entry.substring(0, separator) : entry);
            newHiddenBlocks.add(block);
            if (separator >= 0) {
                newReplacements.put(block, normalize(entry.substring(separator + 1)));
            }
        }
        this.hiddenBlocks = Collections.unmodifiableSet(newHiddenBlocks);
        this.hiddenReplacements = Collections.unmodifiableMap(newReplacements);

        this.proximityDistance = Math.max(0, Math.min(64, c.getInt("proximity-distance", 16)));
    }

    public void reload() {
        load();
        if (plugin.getCompatibility() != null) {
            plugin.getCompatibility().reload();
        }
    }

    static String normalize(String blockName) {
        String name = blockName.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("minecraft:") ? name.substring(10) : name;
    }

    public boolean isWorldProtected(World world) {
        if (world == null) return true;
        Boolean cached = protectedWorlds.get(world.getUID());
        if (cached != null) return cached;

        Set<String> worlds = this.worlds;
        String key = getWorldKey(world);
        boolean result = worlds.isEmpty() || worlds.contains("*")
            || worlds.contains(world.getName().toLowerCase(Locale.ROOT))
            || (key != null && (worlds.contains(key) || worlds.contains(key.substring(key.indexOf(':') + 1))));
        protectedWorlds.put(world.getUID(), result);
        return result;
    }

    static String getWorldKey(World world) {
        try {
            return world.getKey().toString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    static String describeWorld(World world) {
        String key = getWorldKey(world);
        return key == null || key.equalsIgnoreCase(world.getName()) ? world.getName() : world.getName() + " (" + key + ")";
    }

    public Set<String> getWorlds() { return worlds; }
    public Set<String> getHiddenBlocks() { return hiddenBlocks; }
    public Map<String, String> getHiddenReplacements() { return hiddenReplacements; }
    public int getProximityDistance() { return proximityDistance; }
}
