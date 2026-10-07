package com.lhawk.antixray;

import org.bukkit.ChatColor;
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
    // Lidos pelas threads do Netty e das regioes (Folia): os conjuntos sao imutaveis e trocados por inteiro no reload
    private volatile boolean enabled;
    private volatile Set<String> worlds = Collections.emptySet();
    private volatile Set<String> hiddenBlocks = Collections.emptySet();
    // Substituto escolhido na lista ("bloco=substituto"); quem nao tem usa pedra/deepslate/netherrack/end stone
    private volatile Map<String, String> hiddenReplacements = Collections.emptyMap();
    // Resultado de isWorldProtected por mundo; limpo no reload
    private final Map<UUID, Boolean> protectedWorlds = new ConcurrentHashMap<UUID, Boolean>();
    private String normalReplacement;
    private String deepslateReplacement;
    private String netherReplacement;
    private String endReplacement;
    private volatile int proximityDistance;
    private volatile boolean checkBypass;
    private volatile boolean debug;
    private String logLevel;

    private String prefix;
    private String msgReloaded;
    private String msgNoPermission;
    private String msgStatusHeader;
    private String msgStatusEnabled;
    private String msgStatusDisabled;
    private String msgStatusVersion;
    private String msgStatusWorlds;
    private String msgStatusStats;
    private String msgStatusCachedPlayers;
    private String msgStatusCachedChunks;
    private String msgStatusDebug;
    private String msgDebugToggle;

    public Config(AntiXray plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        this.enabled = c.getBoolean("enabled", true);

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

        this.normalReplacement = c.getString("replacements.normal", "STONE");
        this.deepslateReplacement = c.getString("replacements.deepslate", "DEEPSLATE");
        this.netherReplacement = c.getString("replacements.nether", "NETHERRACK");
        this.endReplacement = c.getString("replacements.end", "END_STONE");

        this.proximityDistance = Math.max(0, Math.min(64, c.getInt("proximity-distance", 16)));
        this.checkBypass = c.getBoolean("check-bypass", false);
        this.debug = c.getBoolean("debug", false);
        this.logLevel = c.getString("log-level", "INFO");

        this.prefix = color(c.getString("messages.prefix", "&8[&bAntiXray&8] &7"));
        this.msgReloaded = color(c.getString("messages.reloaded", "&aConfiguracao recarregada com sucesso."));
        this.msgNoPermission = color(c.getString("messages.no-permission", "&cVoce nao tem permissao para executar este comando."));
        this.msgStatusHeader = color(c.getString("messages.status.header", "&b=== &fStatus do AntiXray &b==="));
        this.msgStatusEnabled = color(c.getString("messages.status.enabled", "&7Status: &aAtivado"));
        this.msgStatusDisabled = color(c.getString("messages.status.disabled", "&7Status: &cDesativado"));
        this.msgStatusVersion = color(c.getString("messages.status.version", "&7Protocolo do Servidor: &f%version%"));
        this.msgStatusWorlds = color(c.getString("messages.status.worlds", "&7Mundos Protegidos: &f%worlds%"));
        this.msgStatusStats = color(c.getString("messages.status.stats", "&7Chunks Processados: &f%chunks_proc% &7(Modificados: &a%chunks_mod%&7) | Minerios Ocultados: &e%ores_hidden%"));
        this.msgStatusCachedPlayers = color(c.getString("messages.status.cached-players", "&7Jogadores em Cache: &f%players%"));
        this.msgStatusCachedChunks = color(c.getString("messages.status.cached-chunks", "&7Bordas de Chunks em Cache: &f%chunks%"));
        this.msgStatusDebug = color(c.getString("messages.status.debug-mode", "&7Modo Debug: &f%debug%"));
        this.msgDebugToggle = color(c.getString("messages.debug-toggle", "&7Modo debug &f%state%&7."));
    }

    public void reload() {
        load();
        if (plugin.getCompatibility() != null) {
            plugin.getCompatibility().reload();
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s != null ? s : "");
    }

    // Locale.ROOT: em servidores com locale turco o "I" minusculo vira outro caractere e o nome nao bate
    static String normalize(String blockName) {
        String name = blockName.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("minecraft:") ? name.substring(10) : name;
    }

    public boolean isEnabled() { return enabled; }
    // Aceita o nome do mundo ("world"), a chave ("minecraft:overworld") ou so o nome da chave ("overworld"):
    // no 26.x os mundos tambem sao identificados por chave
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

    // "minecraft:overworld", ou null em versoes sem World#getKey
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
    public String getNormalReplacement() { return normalReplacement; }
    public String getDeepslateReplacement() { return deepslateReplacement; }
    public String getNetherReplacement() { return netherReplacement; }
    public String getEndReplacement() { return endReplacement; }
    public int getProximityDistance() { return proximityDistance; }
    public boolean isCheckBypass() { return checkBypass; }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean debug) {
        this.debug = debug;
        plugin.getConfig().set("debug", debug);
        plugin.saveConfig();
    }
    public String getLogLevel() { return logLevel; }
    public String getPrefix() { return prefix; }
    public String getMsgReloaded() { return msgReloaded; }
    public String getMsgNoPermission() { return msgNoPermission; }
    public String getMsgStatusHeader() { return msgStatusHeader; }
    public String getMsgStatusEnabled() { return msgStatusEnabled; }
    public String getMsgStatusDisabled() { return msgStatusDisabled; }
    public String getMsgStatusVersion() { return msgStatusVersion; }
    public String getMsgStatusWorlds() { return msgStatusWorlds; }
    public String getMsgStatusStats() { return msgStatusStats; }
    public String getMsgStatusCachedPlayers() { return msgStatusCachedPlayers; }
    public String getMsgStatusCachedChunks() { return msgStatusCachedChunks; }
    public String getMsgStatusDebug() { return msgStatusDebug; }
    public String getMsgDebugToggle() { return msgDebugToggle; }
}
