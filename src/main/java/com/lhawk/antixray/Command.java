package com.lhawk.antixray;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class Command implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "AntiXray" + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY;
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "status");

    private final AntiXray plugin;

    public Command(AntiXray plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!sender.hasPermission("antixray.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(PREFIX + "AntiXray " + ChatColor.WHITE + "v" + plugin.getDescription().getVersion());
            sender.sendMessage(ChatColor.GRAY + "/" + label + " reload " + ChatColor.DARK_GRAY + "- reload the configuration");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " status " + ChatColor.DARK_GRAY + "- show statistics");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            plugin.getConfiguration().reload();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
            return true;
        }

        if (sub.equals("status")) {
            sendStatus(sender);
            return true;
        }

        sender.sendMessage(PREFIX + ChatColor.RED + "Unknown subcommand. Use /" + label + " for help.");
        return true;
    }

    private void sendStatus(CommandSender sender) {
        String version = "unknown";
        try {
            version = PacketEvents.getAPI().getServerManager().getVersion().getReleaseName();
        } catch (Throwable ignored) {
        }

        List<String> worlds = new ArrayList<String>();
        for (World world : plugin.getServer().getWorlds()) {
            if (plugin.getConfiguration().isWorldProtected(world)) worlds.add(world.getName());
        }

        ChunkManager chunks = plugin.getChunkManager();
        EntityHider entityHider = plugin.getEntityHider();
        sender.sendMessage(PREFIX + "Status " + ChatColor.WHITE + "v" + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "Server version: " + ChatColor.WHITE + version);
        sender.sendMessage(ChatColor.GRAY + "Protected worlds: " + ChatColor.WHITE + (worlds.isEmpty() ? "none" : String.join(", ", worlds)));
        sender.sendMessage(ChatColor.GRAY + "Chunks processed: " + ChatColor.WHITE + chunks.getChunksProcessed()
            + ChatColor.GRAY + " (modified: " + ChatColor.WHITE + chunks.getChunksModified() + ChatColor.GRAY + ")");
        sender.sendMessage(ChatColor.GRAY + "Blocks hidden: " + ChatColor.WHITE + chunks.getBlocksHidden());
        sender.sendMessage(ChatColor.GRAY + "Entities hidden now: " + ChatColor.WHITE
            + (entityHider != null ? String.valueOf(entityHider.getHiddenCount()) : "disabled (requires Paper or Folia 1.21+)"));
        sender.sendMessage(ChatColor.GRAY + "Players tracked: " + ChatColor.WHITE + plugin.getBlockManager().getCachedPlayerCount());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command cmd, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("antixray.admin")) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (String sub : SUBCOMMANDS) {
            if (sub.startsWith(prefix)) result.add(sub);
        }
        return result;
    }
}
