package com.example.masswhitelist;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MassWhitelistCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.AQUA + "[MassWhitelist] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of("add", "config", "reload");

    private final MassWhitelistPlugin plugin;

    public MassWhitelistCommand(MassWhitelistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("masswhitelist.use")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add": {
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + ChatColor.RED
                            + "Usage: /" + label + " add <name1> <name2> ...");
                    return true;
                }
                // Accept names separated by spaces and/or commas (handy when pasting a list).
                List<String> names = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    for (String part : args[i].split("[,\\s]+")) {
                        if (!part.isBlank()) {
                            names.add(part);
                        }
                    }
                }
                plugin.process(sender, names);
                return true;
            }
            case "config": {
                List<String> names = plugin.configPlayers();
                if (names.isEmpty()) {
                    sender.sendMessage(PREFIX + ChatColor.RED
                            + "The 'players' list in config.yml is empty.");
                    return true;
                }
                plugin.process(sender, names);
                return true;
            }
            case "reload": {
                plugin.reloadConfig();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
                return true;
            }
            default:
                sendUsage(sender, label);
                return true;
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.GRAY + "  /" + label + " add <name1> <name2> ..."
                + ChatColor.DARK_GRAY + "  - whitelist the listed players");
        sender.sendMessage(ChatColor.GRAY + "  /" + label + " config"
                + ChatColor.DARK_GRAY + "  - whitelist everyone in config.yml");
        sender.sendMessage(ChatColor.GRAY + "  /" + label + " reload"
                + ChatColor.DARK_GRAY + "  - reload config.yml");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        return List.of();
    }
}
