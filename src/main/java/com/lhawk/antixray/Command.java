package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class Command implements CommandExecutor, TabCompleter {

    private final AntiXray plugin;

    public Command(AntiXray plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        Config c = plugin.getConfiguration();

        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "=== AntiXray v" + plugin.getDescription().getVersion() + " ===");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " reload " + ChatColor.DARK_GRAY + "- Recarrega a configuracao");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " status " + ChatColor.DARK_GRAY + "- Exibe informacoes do sistema");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " debug " + ChatColor.DARK_GRAY + "- Alterna modo de diagnostico");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("antixray.reload") && !sender.hasPermission("antixray.admin")) {
                sender.sendMessage(c.getPrefix() + c.getMsgNoPermission());
                return true;
            }
            c.reload();
            sender.sendMessage(c.getPrefix() + c.getMsgReloaded());
            return true;
        }

        if (sub.equals("status")) {
            if (!sender.hasPermission("antixray.admin")) {
                sender.sendMessage(c.getPrefix() + c.getMsgNoPermission());
                return true;
            }
            sender.sendMessage(c.getMsgStatusHeader());
            sender.sendMessage(c.isEnabled() ? c.getMsgStatusEnabled() : c.getMsgStatusDisabled());
            String serverVer = "Unknown";
            try {
                serverVer = PacketEvents.getAPI().getServerManager().getVersion().getReleaseName();
            } catch (Throwable ignored) {
            }
            sender.sendMessage(c.getMsgStatusVersion().replace("%version%", serverVer));
            sender.sendMessage(c.getMsgStatusWorlds().replace("%worlds%", c.getWorlds().isEmpty() ? "Todos" : c.getWorlds().toString()));
            sender.sendMessage(c.getMsgStatusStats()
                .replace("%chunks_proc%", String.valueOf(plugin.getChunkManager().getChunksProcessed()))
                .replace("%chunks_mod%", String.valueOf(plugin.getChunkManager().getChunksModified()))
                .replace("%ores_hidden%", String.valueOf(plugin.getChunkManager().getOresHidden()))
            );
            sender.sendMessage(c.getMsgStatusCachedPlayers().replace("%players%", String.valueOf(plugin.getBlockManager().getCachedPlayerCount())));
            sender.sendMessage(c.getMsgStatusCachedChunks().replace("%chunks%", String.valueOf(plugin.getChunkManager().getCachedChunkCount())));
            sender.sendMessage(c.getMsgStatusDebug().replace("%debug%", c.isDebug() ? "Ativado" : "Desativado"));
            // /antixray status <jogador>: inspeciona a conexao de outro jogador (funciona pelo console)
            Player target = sender instanceof Player ? (Player) sender : null;
            if (args.length > 1) {
                target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) sender.sendMessage(ChatColor.RED + "Jogador " + args[1] + " nao esta online.");
            }
            sendDiagnostics(sender, c, target);
            return true;
        }

        if (sub.equals("debug")) {
            if (!sender.hasPermission("antixray.debug") && !sender.hasPermission("antixray.admin")) {
                sender.sendMessage(c.getPrefix() + c.getMsgNoPermission());
                return true;
            }
            boolean newState = !c.isDebug();
            c.setDebug(newState);
            sender.sendMessage(c.getPrefix() + c.getMsgDebugToggle().replace("%state%", newState ? "ativado" : "desativado"));
            return true;
        }

        sender.sendMessage(c.getPrefix() + ChatColor.RED + "Subcomando desconhecido. Use /" + label + " para ver a ajuda.");
        return true;
    }

    // Mostra onde o processamento para: pacotes que chegam do PacketEvents, chunks ignorados e o mundo de quem usou o comando
    private void sendDiagnostics(CommandSender sender, Config c, Player target) {
        plugin.ensurePacketListener();
        ChunkManager chunks = plugin.getChunkManager();
        sender.sendMessage(ChatColor.GRAY + "Pacotes recebidos do PacketEvents: " + ChatColor.WHITE + chunks.getPacketsSeen()
            + ChatColor.GRAY + " (de chunk: " + ChatColor.WHITE + chunks.getChunkPacketsSeen()
            + ChatColor.GRAY + ", tipo desconhecido: " + ChatColor.WHITE + chunks.getUnknownPacketsSeen()
            + ChatColor.GRAY + ", vindos do cliente: " + ChatColor.WHITE + chunks.getPacketsReceived() + ChatColor.GRAY + ")");

        // Texto tambem vai para o console, para poder copiar
        String packetEvents = describePacketEvents();
        sender.sendMessage(ChatColor.GRAY + "PacketEvents: " + ChatColor.WHITE + packetEvents);
        plugin.getLogger().info("[status] PacketEvents: " + packetEvents);
        if (target != null) {
            String connection = describeConnection(target);
            sender.sendMessage(ChatColor.GRAY + "Conexao de " + target.getName() + " (vista pelo PacketEvents): " + ChatColor.WHITE + connection);
            plugin.getLogger().info("[status] Conexao de " + target.getName() + " (PacketEvents): " + connection);
            String realChannel = Diagnostics.describeRealChannel(target);
            sender.sendMessage(ChatColor.GRAY + "Conexao de " + target.getName() + " (canal real): " + ChatColor.WHITE + realChannel);
            plugin.getLogger().info("[status] Conexao de " + target.getName() + " (canal real): " + realChannel);
        }
        String copies = Diagnostics.getEmbeddedCopies();
        sender.sendMessage(ChatColor.GRAY + "Plugins com PacketEvents embutido: " + ChatColor.WHITE + copies);
        plugin.getLogger().info("[status] Plugins com PacketEvents embutido: " + copies);
        List<String> log = Diagnostics.getPacketEventsLog();
        sender.sendMessage(ChatColor.GRAY + "Avisos do PacketEvents no log: " + ChatColor.WHITE + (log.isEmpty() ? "nenhum" : log.size() + " (veja o console)"));
        for (String line : log) {
            plugin.getLogger().info("[status] Aviso do PacketEvents: " + line);
        }

        Map<String, AtomicLong> skipped = chunks.getSkipped();
        if (skipped.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Chunks ignorados: " + ChatColor.WHITE + "nenhum");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Chunks ignorados:");
            for (Map.Entry<String, AtomicLong> e : skipped.entrySet()) {
                sender.sendMessage(ChatColor.DARK_GRAY + " - " + ChatColor.YELLOW + e.getValue().get() + "x " + ChatColor.GRAY + e.getKey());
            }
        }

        if (target != null) {
            World world = target.getWorld();
            boolean protectedWorld = c.isWorldProtected(world);
            sender.sendMessage(ChatColor.GRAY + "Mundo de " + target.getName() + ": " + ChatColor.WHITE + Config.describeWorld(world) + ChatColor.GRAY + " - "
                + (protectedWorld ? ChatColor.GREEN + "protegido" : ChatColor.RED + "NAO protegido (adicione em 'worlds')"));
        }
    }

    // Qual instancia do PacketEvents o AntiXray usa e se o listener esta nela
    private String describePacketEvents() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null) return "nao carregado";
        Object owner = api.getPlugin();
        String ownerName = owner instanceof Plugin ? ((Plugin) owner).getName() : String.valueOf(owner);
        String jar = "?";
        try {
            String path = PacketEvents.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            jar = path.substring(path.lastIndexOf('/') + 1);
        } catch (Throwable ignored) {
        }
        return "v" + api.getVersion() + " | dono: " + ownerName + " | jar: " + jar + " | iniciado: " + api.isInitialized()
            + " | handler esperado: " + PacketEvents.ENCODER_NAME
            + " | listener na instancia atual: " + (plugin.isPacketListenerCurrent() ? "sim" : "NAO");
    }

    // Mostra se o PacketEvents achou a conexao do jogador e quais handlers do Netty estao nela
    private String describeConnection(Player player) {
        try {
            Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
            if (channel == null) return "o PacketEvents nao encontrou o canal deste jogador";
            boolean hasUser = PacketEvents.getAPI().getPlayerManager().getUser(player) != null;
            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            Object names = pipeline.getClass().getMethod("names").invoke(pipeline);
            return "usuario no PacketEvents: " + (hasUser ? "sim" : "NAO") + " | handlers: " + names;
        } catch (Throwable t) {
            return "erro ao inspecionar: " + t;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<String>(Arrays.asList("reload", "status", "debug"));
            List<String> result = new ArrayList<String>();
            String prefix = args[0].toLowerCase();
            for (String s : list) {
                if (s.startsWith(prefix)) {
                    result.add(s);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
