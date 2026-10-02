package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
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
    public void onLoad() {
        instance = this;
        try {
            if (!PacketEvents.getAPI().isLoaded()) {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
                PacketEvents.getAPI().getSettings().checkForUpdates(false).bStats(false);
                PacketEvents.getAPI().load();
            }
        } catch (Throwable t) {
            getLogger().warning("Falha ao carregar PacketEvents: " + t.getMessage());
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        this.config = new Config(this);
        this.compatibility = new Compatibility(this);
        this.blockManager = new BlockManager(this);
        this.chunkManager = new ChunkManager(this);
        this.packetHandler = new PacketHandler(this);

        try {
            if (!PacketEvents.getAPI().isInitialized()) {
                PacketEvents.getAPI().init();
            }
            PacketEvents.getAPI().getEventManager().registerListener(this.packetHandler);
        } catch (Throwable t) {
            getLogger().severe("Erro ao registrar PacketHandler: " + t.getMessage());
        }

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
