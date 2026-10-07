package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;

public class AntiXray extends JavaPlugin {

    private static AntiXray instance;

    private Config config;
    private Compatibility compatibility;
    private BlockManager blockManager;
    private ChunkManager chunkManager;
    private PacketHandler packetHandler;

    public static AntiXray getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.config = new Config(this);
        this.compatibility = new Compatibility(this);
        this.blockManager = new BlockManager(this);
        this.chunkManager = new ChunkManager(this);
        this.packetHandler = new PacketHandler(this);

        // O PacketEvents e um plugin separado (depend no plugin.yml) e ja vem carregado e iniciado
        PacketEvents.getAPI().getEventManager().registerListener(this.packetHandler);

        getServer().getPluginManager().registerEvents(this.blockManager, this);
        getServer().getPluginManager().registerEvents(this.chunkManager, this);

        Command cmd = new Command(this);
        if (getCommand("antixray") != null) {
            getCommand("antixray").setExecutor(cmd);
            getCommand("antixray").setTabCompleter(cmd);
        }

        String serverVer = "Unknown";
        try {
            serverVer = PacketEvents.getAPI().getServerManager().getVersion().getReleaseName();
        } catch (Throwable ignored) {
        }
        getLogger().info("AntiXray ativado com sucesso. Protocolo: " + serverVer);
    }

    @Override
    public void onDisable() {
        if (this.packetHandler != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(this.packetHandler);
            } catch (Throwable ignored) {
            }
        }
        getLogger().info("AntiXray desativado.");
    }

    public Config getConfiguration() {
        return config;
    }

    public Compatibility getCompatibility() {
        return compatibility;
    }

    public BlockManager getBlockManager() {
        return blockManager;
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }
}
