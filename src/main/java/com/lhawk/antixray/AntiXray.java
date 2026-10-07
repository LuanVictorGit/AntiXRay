package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class AntiXray extends JavaPlugin {

    private static AntiXray instance;

    private Config config;
    private Compatibility compatibility;
    private BlockManager blockManager;
    private ChunkManager chunkManager;
    private PacketHandler packetHandler;
    private PacketEventsAPI<?> registeredApi;

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
        ensurePacketListener();

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
        logWorlds();
        Diagnostics.scanInBackground(this);
    }

    // Mostra no console quais mundos carregados estao na lista 'worlds' (nome ou chave)
    private void logWorlds() {
        List<String> protectedWorlds = new ArrayList<String>();
        List<String> otherWorlds = new ArrayList<String>();
        for (World world : getServer().getWorlds()) {
            (config.isWorldProtected(world) ? protectedWorlds : otherWorlds).add(Config.describeWorld(world));
        }
        getLogger().info("Mundos protegidos: " + protectedWorlds + " | sem protecao: " + otherWorlds);
        if (protectedWorlds.isEmpty()) {
            getLogger().warning("Nenhum mundo carregado esta na lista 'worlds' do config.yml; nenhum chunk sera processado.");
        }
    }

    // Registra o listener na instancia atual do PacketEvents. Se outro plugin trocar a instancia
    // (PacketEvents.setAPI), o listener ficaria numa instancia que nao recebe pacotes; aqui ele e movido
    public synchronized void ensurePacketListener() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || api == registeredApi) return;
        if (registeredApi != null) {
            getLogger().warning("A instancia do PacketEvents foi trocada por outro plugin; registrando o AntiXray na nova.");
            try {
                registeredApi.getEventManager().unregisterListener(this.packetHandler);
            } catch (Throwable ignored) {
            }
        }
        api.getEventManager().registerListener(this.packetHandler);
        registeredApi = api;
    }

    public synchronized boolean isPacketListenerCurrent() {
        return registeredApi != null && registeredApi == PacketEvents.getAPI();
    }

    @Override
    public void onDisable() {
        synchronized (this) {
            if (this.registeredApi != null) {
                try {
                    this.registeredApi.getEventManager().unregisterListener(this.packetHandler);
                } catch (Throwable ignored) {
                }
                this.registeredApi = null;
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
