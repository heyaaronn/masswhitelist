package com.example.masswhitelist;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MassWhitelistPlugin extends JavaPlugin {

    private static final String PREFIX = ChatColor.AQUA + "[MassWhitelist] " + ChatColor.RESET;
    private static final int MAX_INLINE_FAILURES = 10;

    private MojangResolver resolver;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.resolver = new MojangResolver(getLogger());

        MassWhitelistCommand command = new MassWhitelistCommand(this);
        PluginCommand pc = getCommand("masswhitelist");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }
        getLogger().info("MassWhitelist enabled.");
    }

    public List<String> configPlayers() {
        return getConfig().getStringList("players");
    }

    /**
     * Resolves the supplied names off-thread, then applies whitelist changes on the
     * main thread and reports the result (including a file of any failed usernames).
     */
    public void process(CommandSender sender, Collection<String> rawNames) {
        // Trim, drop blanks, and de-duplicate case-insensitively while keeping the first spelling seen.
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String raw : rawNames) {
            if (raw == null) {
                continue;
            }
            String name = raw.trim();
            if (!name.isEmpty()) {
                unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        List<String> names = new ArrayList<>(unique.values());

        if (names.isEmpty()) {
            respond(sender, PREFIX + ChatColor.RED + "No player names were supplied.");
            return;
        }

        boolean verify = getConfig().getBoolean("verify-with-mojang", true);
        respond(sender, PREFIX + ChatColor.YELLOW + "Processing " + names.size() + " name(s)"
                + (verify ? " (verifying with Mojang)" : " (offline mode \u2013 no verification)") + "...");

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ResolutionResult result = resolveAll(names, verify);
            getServer().getScheduler().runTask(this, () -> applyAndReport(sender, result));
        });
    }

    private ResolutionResult resolveAll(List<String> names, boolean verify) {
        ResolutionResult result = new ResolutionResult();

        List<String> wellFormed = new ArrayList<>();
        for (String name : names) {
            if (MojangResolver.isValidFormat(name)) {
                wellFormed.add(name);
            } else {
                result.failed.put(name, "INVALID_FORMAT (must be 3-16 chars: letters, digits, underscore)");
            }
        }

        if (!verify) {
            // Offline mode: cannot verify against Mojang, so accept every well-formed name.
            for (String name : wellFormed) {
                result.valid.add(new ResolvedName(name, null));
            }
            return result;
        }

        MojangResolver.ResolveOutcome outcome = resolver.resolve(wellFormed);
        for (String name : wellFormed) {
            String key = name.toLowerCase(Locale.ROOT);
            MojangResolver.Profile profile = outcome.found.get(key);
            if (profile != null) {
                result.valid.add(new ResolvedName(profile.canonicalName(), profile.uuid()));
            } else if (outcome.errored.contains(key)) {
                result.failed.put(name, "LOOKUP_ERROR (could not reach Mojang \u2013 try again later)");
            } else {
                result.failed.put(name, "NOT_FOUND (no Minecraft account with this username)");
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation") // getOfflinePlayer(String) is only used in offline mode, where it does no web lookup
    private void applyAndReport(CommandSender sender, ResolutionResult result) {
        int added = 0;
        for (ResolvedName entry : result.valid) {
            try {
                OfflinePlayer offline = (entry.uuid != null)
                        ? Bukkit.getOfflinePlayer(entry.uuid)
                        : Bukkit.getOfflinePlayer(entry.name);
                offline.setWhitelisted(true);
                added++;
            } catch (Exception ex) {
                result.failed.put(entry.name, "WHITELIST_ERROR (" + ex.getMessage() + ")");
            }
        }

        if (getConfig().getBoolean("reload-whitelist-after", true)) {
            try {
                Bukkit.reloadWhitelist();
            } catch (Throwable ignored) {
                // some forks may behave differently; the entries are already saved either way
            }
        }

        respond(sender, PREFIX + ChatColor.GREEN + "Added " + added + " player(s) to the whitelist.");

        if (!result.failed.isEmpty()) {
            Path file = writeFailuresFile(result.failed);
            respond(sender, PREFIX + ChatColor.RED + result.failed.size()
                    + " username(s) could not be added:");

            int shown = 0;
            for (Map.Entry<String, String> bad : result.failed.entrySet()) {
                if (shown++ >= MAX_INLINE_FAILURES) {
                    respond(sender, PREFIX + ChatColor.GRAY + "  ... and "
                            + (result.failed.size() - MAX_INLINE_FAILURES) + " more.");
                    break;
                }
                respond(sender, ChatColor.RED + "  - " + bad.getKey()
                        + ChatColor.GRAY + "  (" + bad.getValue() + ")");
            }

            if (file != null) {
                respond(sender, PREFIX + ChatColor.YELLOW + "Full report saved to: "
                        + ChatColor.WHITE + file);
            }
        }
    }

    private Path writeFailuresFile(Map<String, String> failures) {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder for the failure report.");
            return null;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = getDataFolder().toPath().resolve("failed-usernames-" + stamp + ".txt");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# MassWhitelist - usernames that could NOT be added");
            writer.newLine();
            writer.write("# Generated: " + LocalDateTime.now());
            writer.newLine();
            writer.write("# Format: <username>  ->  <reason>");
            writer.newLine();
            writer.newLine();
            for (Map.Entry<String, String> bad : failures.entrySet()) {
                writer.write(bad.getKey() + "  ->  " + bad.getValue());
                writer.newLine();
            }
            return file;
        } catch (IOException ex) {
            getLogger().warning("Failed to write failure report: " + ex.getMessage());
            return null;
        }
    }

    private void respond(CommandSender sender, String message) {
        // Always mirror to console so there is a record even if a player logs off mid-run.
        if (!(sender instanceof Player)) {
            getLogger().info(ChatColor.stripColor(message));
            return;
        }
        Player player = (Player) sender;
        if (player.isOnline()) {
            player.sendMessage(message);
        } else {
            getLogger().info(ChatColor.stripColor(message));
        }
    }

    /** A name that passed validation, with its UUID (null in offline mode). */
    private static final class ResolvedName {
        final String name;
        final UUID uuid;

        ResolvedName(String name, UUID uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }

    /** Working set produced off-thread and consumed on the main thread. */
    private static final class ResolutionResult {
        final List<ResolvedName> valid = new ArrayList<>();
        final LinkedHashMap<String, String> failed = new LinkedHashMap<>();
    }
}
