package com.veyrion.character.command;

import com.veyrion.character.awakening.AltarAdminHandler;
import com.veyrion.character.awakening.AwakeningService;
import com.veyrion.character.config.Messages;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.race.RaceChanceCalculator;
import com.veyrion.character.race.RaceDefinition;
import com.veyrion.character.race.RaceService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /raceadmin set <player> <race> | reset <player> | chances | altar set|remove|info|reload}. */
public final class RaceAdminCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.race.admin";

    private final VeyrionCharacterPlugin plugin;
    private final RaceService races;
    private final AltarAdminHandler altar;

    public RaceAdminCommand(VeyrionCharacterPlugin plugin, RaceService races, AwakeningService awakening) {
        this.plugin = plugin;
        this.races = races;
        this.altar = new AltarAdminHandler(awakening);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "race.admin.usage", Placeholder.unparsed("label", label));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> set(sender, label, args);
            case "reset" -> reset(sender, label, args);
            case "chances" -> chances(sender);
            case "altar" -> altar.handle(sender, label, java.util.Arrays.copyOfRange(args, 1, args.length));
            default -> messages.send(sender, "race.admin.usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void set(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length < 3) {
            messages.send(sender, "race.admin.usage", Placeholder.unparsed("label", label));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            messages.send(sender, "command.player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        var settings = races.registry().settings();
        RaceDefinition race = settings.find(args[2]);
        if (race == null) {
            messages.send(sender, "race.admin.unknown-race",
                    Placeholder.unparsed("race", args[2]),
                    Placeholder.unparsed("list", String.join(", ", settings.races().keySet())));
            return;
        }

        UUID uuid = target.getUniqueId();
        String targetName = args[1];
        races.setRace(uuid, race.id()).whenComplete((updated, error) -> {
            Messages current = plugin.messages();
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to set race for " + targetName, error);
                current.send(sender, "command.data-error");
                return;
            }
            if (!updated) {
                current.send(sender, "command.no-character", Placeholder.unparsed("player", targetName));
                return;
            }
            current.send(sender, "race.admin.set-success",
                    Placeholder.unparsed("player", targetName),
                    Placeholder.component("race", race.displayComponent()));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                current.send(online, "race.admin.set-notify", Placeholder.component("race", race.displayComponent()));
            }
        });
    }

    private void reset(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length < 2) {
            messages.send(sender, "race.admin.usage", Placeholder.unparsed("label", label));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            messages.send(sender, "command.player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        UUID uuid = target.getUniqueId();
        String targetName = args[1];
        races.reset(uuid).whenComplete((updated, error) -> {
            Messages current = plugin.messages();
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reset race for " + targetName, error);
                current.send(sender, "command.data-error");
                return;
            }
            if (!updated) {
                current.send(sender, "command.no-character", Placeholder.unparsed("player", targetName));
                return;
            }
            current.send(sender, "race.admin.reset-success", Placeholder.unparsed("player", targetName));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                current.send(online, "race.admin.reset-notify");
            }
        });
    }

    private void chances(CommandSender sender) {
        races.chances().whenComplete((report, error) -> {
            Messages messages = plugin.messages();
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to calculate race chances", error);
                messages.send(sender, "command.data-error");
                return;
            }
            if (report.chances().isEmpty()) {
                messages.send(sender, "race.no-races");
                return;
            }
            var settings = races.registry().settings();
            sender.sendMessage(messages.get("race.admin.chances-header",
                    Placeholder.unparsed("counted", Long.toString(report.counted()))));
            for (RaceChanceCalculator.Chance chance : report.chances()) {
                sender.sendMessage(messages.get("race.admin.chances-line",
                        Placeholder.component("race", settings.display(chance.id())),
                        Placeholder.unparsed("weight", number(chance.weight())),
                        Placeholder.unparsed("population", Long.toString(chance.population())),
                        Placeholder.unparsed("expected", number(chance.expected())),
                        Placeholder.unparsed("multiplier", number(chance.multiplier())),
                        Placeholder.unparsed("chance", number(chance.chance() * 100))));
            }
            if (!report.dynamic()) {
                sender.sendMessage(messages.get("race.admin.chances-dynamic-off"));
            }
            if (report.other() > 0) {
                sender.sendMessage(messages.get("race.admin.chances-other",
                        Placeholder.unparsed("count", Long.toString(report.other()))));
            }
        });
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("set", "reset", "chances", "altar").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("altar")) {
            return altar.tabComplete(java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2 && (sub.equals("set") || sub.equals("reset"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && sub.equals("set")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return races.registry().settings().races().keySet().stream()
                    .filter(id -> id.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
