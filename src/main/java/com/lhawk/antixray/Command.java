package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
