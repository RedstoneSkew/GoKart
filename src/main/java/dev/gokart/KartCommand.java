package dev.gokart;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** /kart duel <player> | accept | decline | cancel | give | reload */
public final class KartCommand implements BasicCommand {

    private final GoKartPlugin plugin;

    KartCommand(GoKartPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "gokart.use";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!admin(sender)) return;
                plugin.reloadConfig();
                plugin.say(sender, "Config reloaded.");
                return;
            }
            case "give" -> {
                if (!admin(sender)) return;
                give(source, args);
                return;
            }
            default -> { }
        }

        if (!(source.getExecutor() instanceof Player player)) {
            plugin.sayError(sender, "Only players can race.");
            return;
        }
        switch (sub) {
            case "duel", "race", "challenge" -> {
                if (args.length < 2) {
                    plugin.sayError(player, "Usage: /kart duel <player>");
                    return;
                }
                if (args[1].equalsIgnoreCase("cancel")) {
                    plugin.races().cancel(player);
                    return;
                }
                Player opponent = Bukkit.getPlayerExact(args[1]);
                if (opponent == null) {
                    plugin.sayError(player, "That player isn't online.");
                    return;
                }
                plugin.races().challenge(player, opponent);
            }
            case "accept" -> plugin.races().accept(player);
            case "wins", "stats", "trophy" -> {
                if (args.length >= 2) {
                    var target = Bukkit.getOfflinePlayer(args[1]);
                    plugin.trophies().showWins(player, target.getUniqueId(),
                            target.getName() != null ? target.getName() : args[1]);
                } else {
                    plugin.trophies().showWins(player, player.getUniqueId(), player.getName());
                }
            }
            case "top", "leaderboard" -> plugin.trophies().showTop(player);
            case "decline", "deny" -> plugin.races().decline(player);
            case "cancel", "leave", "quit" -> plugin.races().cancel(player);
            default -> help(player);
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("gokart.admin")) return true;
        plugin.sayError(sender, "You don't have permission to do that.");
        return false;
    }

    private void give(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                plugin.sayError(sender, "Amount must be a number.");
                return;
            }
        }
        Player target = args.length >= 4 ? Bukkit.getPlayerExact(args[3])
                : source.getExecutor() instanceof Player p ? p : null;
        if (target == null) {
            plugin.sayError(sender, "Usage: /kart give <rubber|tires> [amount] [player]");
            return;
        }
        ItemStack item = plugin.items().create(type, amount);
        if (item == null) {
            plugin.sayError(sender, "Unknown item. Use rubber or tires.");
            return;
        }
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        plugin.say(sender, "Gave " + amount + " " + type + " to " + target.getName() + ".");
    }

    private void help(CommandSender to) {
        to.sendMessage(Component.text("—— GoKart ", NamedTextColor.GOLD)
                .append(Component.text("by Redstoneskew", NamedTextColor.YELLOW))
                .append(Component.text(" ——", NamedTextColor.GOLD)));
        line(to, "/kart duel <player>", "challenge someone near you to a race");
        line(to, "/kart accept | decline", "answer a challenge (or click the buttons)");
        line(to, "/kart cancel", "back out of a challenge or forfeit a race");
        line(to, "/kart wins [player]", "race wins and trophy cup");
        line(to, "/kart top", "top 10 racers");
        to.sendMessage(Component.text("Hold your Kart Controller: W gas, S brake, A/D steer, SPACE boost, SNEAK handbrake.",
                NamedTextColor.GRAY));
        to.sendMessage(Component.text("Carry Racing Tires (8 Rubber; smelt sugar cane for Rubber) for a faster kart.",
                NamedTextColor.GRAY));
    }

    private static void line(CommandSender to, String cmd, String what) {
        to.sendMessage(Component.text(cmd, NamedTextColor.YELLOW)
                .append(Component.text(" — " + what, NamedTextColor.GRAY)));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> subs = new ArrayList<>(List.of("duel", "accept", "decline", "cancel", "wins", "top", "help"));
            if (sender.hasPermission("gokart.admin")) subs.addAll(List.of("give", "reload"));
            return filter(subs.stream(), prefix);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "duel", "race", "challenge" -> filter(Stream.concat(Stream.of("cancel"),
                        Bukkit.getOnlinePlayers().stream().map(Player::getName)
                                .filter(n -> !n.equals(sender.getName()))), prefix);
                case "give" -> filter(Stream.of(CustomItems.RUBBER, CustomItems.TIRES), prefix);
                case "wins", "stats", "trophy" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), prefix);
                default -> List.of();
            };
        }
        if (sub.equals("give") && args.length == 3) return List.of("1", "8", "16", "64");
        if (sub.equals("give") && args.length == 4) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), prefix);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
