package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class AntiXray extends JavaPlugin {

    private Config config;
    private Compatibility compatibility;
    private BlockManager blockManager;
    private ChunkManager chunkManager;
    private PacketHandler packetHandler;
    private EntityHider entityHider;
    private PacketEventsAPI<?> registeredApi;

    @Override
    public void onEnable() {
        this.config = new Config(this);
        this.compatibility = new Compatibility(this);
        this.blockManager = new BlockManager(this);
        this.chunkManager = new ChunkManager(this);
        this.packetHandler = new PacketHandler(this);
        ensurePacketListener();

        getServer().getPluginManager().registerEvents(blockManager, this);
        getServer().getPluginManager().registerEvents(chunkManager, this);

        try {
            entityHider = EntityHider.create(this);
        } catch (Throwable ignored) {
        }
        if (entityHider != null) {
            entityHider.register();
        } else {
            getLogger().info("Entity hiding requires Paper or Folia 1.21+, only blocks will be hidden.");
        }

        PluginCommand command = getCommand("antixray");
        if (command != null) {
            Command executor = new Command(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        List<String> protectedWorlds = new ArrayList<String>();
        for (World world : getServer().getWorlds()) {
            if (config.isWorldProtected(world)) protectedWorlds.add(Config.describeWorld(world));
        }
        if (protectedWorlds.isEmpty()) {
            getLogger().warning("None of the loaded worlds is listed in config.yml, nothing will be hidden.");
        } else {
            getLogger().info("Protecting " + protectedWorlds);
        }
    }

    public synchronized void ensurePacketListener() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || api == registeredApi) return;
        if (registeredApi != null) {
            try {
                registeredApi.getEventManager().unregisterListener(packetHandler);
            } catch (Throwable ignored) {
            }
        }
        api.getEventManager().registerListener(packetHandler);
        registeredApi = api;
    }

    @Override
    public void onDisable() {
        if (entityHider != null) {
            entityHider.shutdown();
        }
        synchronized (this) {
            if (registeredApi != null) {
                try {
                    registeredApi.getEventManager().unregisterListener(packetHandler);
                } catch (Throwable ignored) {
                }
                registeredApi = null;
            }
        }
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

    public EntityHider getEntityHider() {
        return entityHider;
    }
}
