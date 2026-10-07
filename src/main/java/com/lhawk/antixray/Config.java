package com.lhawk.antixray;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class Config {

    private final AntiXray plugin;
    // Lidos pelas threads do Netty e das regioes (Folia): os conjuntos sao imutaveis e trocados por inteiro no reload
    private volatile boolean enabled;
    private volatile Set<String> worlds = Collections.emptySet();
    private volatile Set<String> hiddenBlocks = Collections.emptySet();
    private String normalReplacement;
    private String deepslateReplacement;
    private String netherReplacement;
    private String endReplacement;
    private int revealDistance;
    private int maxCacheSize;
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

        Set<String> newHiddenBlocks = new HashSet<String>();
        for (String h : c.getStringList("hidden-blocks")) {
            newHiddenBlocks.add(normalize(h));
        }
        this.hiddenBlocks = Collections.unmodifiableSet(newHiddenBlocks);

        this.normalReplacement = c.getString("replacements.normal", "STONE");
        this.deepslateReplacement = c.getString("replacements.deepslate", "DEEPSLATE");
        this.netherReplacement = c.getString("replacements.nether", "NETHERRACK");
        this.endReplacement = c.getString("replacements.end", "END_STONE");

        this.revealDistance = c.getInt("reveal-distance", 2);
        this.maxCacheSize = c.getInt("max-cache-size", 4096);
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
    public boolean isWorldProtected(String worldName) {
        if (worldName == null) return true;
        Set<String> worlds = this.worlds;
        return worlds.isEmpty() || worlds.contains("*") || worlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
    public Set<String> getWorlds() { return worlds; }
    public Set<String> getHiddenBlocks() { return hiddenBlocks; }
    public String getNormalReplacement() { return normalReplacement; }
    public String getDeepslateReplacement() { return deepslateReplacement; }
    public String getNetherReplacement() { return netherReplacement; }
    public String getEndReplacement() { return endReplacement; }
    public int getRevealDistance() { return revealDistance; }
    public int getMaxCacheSize() { return maxCacheSize; }
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
