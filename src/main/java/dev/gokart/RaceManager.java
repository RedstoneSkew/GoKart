package dev.gokart;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /kart duel: two players race go-karts to a finish line.
 * When the countdown starts each player is handed a Kart Controller and frozen in place;
 * while they hold it, W/A/S/D drive their kart's kart, SPACE boosts and SNEAK brakes.
 */
public final class RaceManager implements Listener {

    private record Challenge(UUID from, UUID to, long expiresAtTick) {}

    private enum Phase { COUNTDOWN, RACING, FINISHED }

    private final GoKartPlugin plugin;
    private final NamespacedKey controllerKey;
    private final NamespacedKey savedWalkKey;
    private final NamespacedKey savedFlyKey;
    private final NamespacedKey savedJumpKey;
    private final NamespacedKey savedModeKey;
    private final NamespacedKey savedSpotKey;

    /** Pending challenges, keyed by the player who was challenged. */
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    /** Running races, keyed by each of the two players in it. */
    private final Map<UUID, Race> races = new HashMap<>();

    RaceManager(GoKartPlugin plugin) {
        this.plugin = plugin;
        this.controllerKey = new NamespacedKey(plugin, "kart_controller");
        this.savedWalkKey = new NamespacedKey(plugin, "race_saved_walk");
        this.savedFlyKey = new NamespacedKey(plugin, "race_saved_fly");
        this.savedJumpKey = new NamespacedKey(plugin, "race_saved_jump");
        this.savedModeKey = new NamespacedKey(plugin, "race_saved_gamemode");
        this.savedSpotKey = new NamespacedKey(plugin, "race_saved_spot");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // Players already online (e.g. after /reload or a crash mid-race): make sure nobody stays frozen.
        for (Player p : Bukkit.getOnlinePlayers()) {
            unfreeze(p);
            restoreCamera(p);
        }
    }

    // ================================================================ commands

    public void challenge(Player from, Player to) {
        if (to.equals(from)) {
            plugin.sayError(from, "You can't race yourself... yet.");
            return;
        }
        if (races.containsKey(from.getUniqueId())) {
            plugin.sayError(from, "You're already in a race.");
            return;
        }
        if (races.containsKey(to.getUniqueId())) {
            plugin.sayError(from, to.getName() + " is already racing.");
            return;
        }
        if (!to.hasPermission("gokart.use")) {
            plugin.sayError(from, to.getName() + " can't race.");
            return;
        }
        if (!closeEnough(from, to)) {
            plugin.sayError(from, to.getName() + " needs to be standing near you to race.");
            return;
        }
        int seconds = plugin.getConfig().getInt("race.challenge-seconds", 30);
        challenges.put(to.getUniqueId(),
                new Challenge(from.getUniqueId(), to.getUniqueId(), Bukkit.getCurrentTick() + seconds * 20L));

        plugin.say(from, "Challenged " + to.getName() + " to a go-kart race! Waiting for them to accept...");
        to.sendMessage(GoKartPlugin.PREFIX
                .append(Component.text(from.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" challenged you to a go-kart race! ", NamedTextColor.GRAY))
                .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/kart accept"))
                        .hoverEvent(HoverEvent.showText(Component.text("Race " + from.getName()))))
                .append(Component.text(" "))
                .append(Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/kart decline"))));
        to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    public void accept(Player to) {
        Challenge c = challenges.remove(to.getUniqueId());
        if (c == null) {
            plugin.sayError(to, "Nobody has challenged you (or it expired).");
            return;
        }
        Player from = Bukkit.getPlayer(c.from());
        if (from == null) {
            plugin.sayError(to, "They left the server.");
            return;
        }
        if (races.containsKey(from.getUniqueId()) || races.containsKey(to.getUniqueId())) {
            plugin.sayError(to, "One of you is already racing.");
            return;
        }
        if (!closeEnough(from, to)) {
            plugin.sayError(to, "Go stand near " + from.getName() + " first, then accept again.");
            plugin.sayError(from, to.getName() + " needs to be near you to race.");
            challenges.put(to.getUniqueId(), c);
            return;
        }
        // Clicking [Accept] leaves the chat window open; snap both players out of it so they see the race.
        closeChat(to);
        closeChat(from);
        Race race = new Race(from, to);
        races.put(from.getUniqueId(), race);
        races.put(to.getUniqueId(), race);
    }

    /**
     * The server can't close the chat box directly, but opening a screen replaces it on the client,
     * and closing that screen leaves nothing open.
     */
    private void closeChat(Player p) {
        p.openInventory(Bukkit.createInventory(null, 9));
        p.closeInventory();
    }

    public void decline(Player to) {
        Challenge c = challenges.remove(to.getUniqueId());
        if (c == null) {
            plugin.sayError(to, "You don't have a race challenge.");
            return;
        }
        plugin.say(to, "Challenge declined.");
        Player from = Bukkit.getPlayer(c.from());
        if (from != null) plugin.sayError(from, to.getName() + " declined your race.");
    }

    public void cancel(Player player) {
        Race race = races.get(player.getUniqueId());
        if (race != null) {
            race.forfeit(player.getUniqueId(), "gave up");
            return;
        }
        boolean removed = challenges.values().removeIf(c -> c.from().equals(player.getUniqueId()));
        if (removed) plugin.say(player, "Challenge cancelled.");
        else plugin.sayError(player, "You're not in a race.");
    }

    public void stopAll() {
        for (Race race : new HashSet<>(races.values())) race.cleanup();
        races.clear();
        challenges.clear();
    }

    private boolean closeEnough(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return false;
        double max = plugin.getConfig().getDouble("race.max-start-distance", 30.0);
        return a.getLocation().distanceSquared(b.getLocation()) <= max * max;
    }

    // ================================================================ controller item + freezing

    private ItemStack makeController(NamedTextColor color) {
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Kart Controller", color, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("W  ", "gas"),
                    line("S  ", "brake / reverse"),
                    line("A D  ", "steer"),
                    line("SPACE  ", "boost (recharges)"),
                    line("SNEAK  ", "handbrake"),
                    Component.text("Keep it in your hand to drive!", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(controllerKey, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    private static Component line(String key, String what) {
        return Component.text(key, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(what, NamedTextColor.GRAY));
    }

    private boolean isController(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(controllerKey, PersistentDataType.BOOLEAN);
    }

    /** Stop the player walking/jumping so W/A/S/D/SPACE only drive the kart. Saved on the player in case of a crash. */
    private void freeze(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(savedWalkKey, PersistentDataType.FLOAT)) {
            pdc.set(savedWalkKey, PersistentDataType.FLOAT, p.getWalkSpeed());
            pdc.set(savedFlyKey, PersistentDataType.FLOAT, p.getFlySpeed());
            AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
            if (jump != null) pdc.set(savedJumpKey, PersistentDataType.DOUBLE, jump.getBaseValue());
        }
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.setBaseValue(0);
    }

    private void unfreeze(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Float walk = pdc.get(savedWalkKey, PersistentDataType.FLOAT);
        Float fly = pdc.get(savedFlyKey, PersistentDataType.FLOAT);
        Double jumpValue = pdc.get(savedJumpKey, PersistentDataType.DOUBLE);
        if (walk != null) p.setWalkSpeed(walk);
        if (fly != null) p.setFlySpeed(fly);
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jumpValue != null && jump != null) jump.setBaseValue(jumpValue);
        pdc.remove(savedWalkKey);
        pdc.remove(savedFlyKey);
        pdc.remove(savedJumpKey);
        // Remove any leftover controllers.
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isController(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    // ================================================================ chase camera (spectator) bookkeeping

    /** Remember the player's game mode and spot (on the player, so a crash mid-race can't strand them). */
    private void saveForCamera(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(savedModeKey, PersistentDataType.STRING)) return;
        Location l = p.getLocation();
        pdc.set(savedModeKey, PersistentDataType.STRING, p.getGameMode().name());
        pdc.set(savedSpotKey, PersistentDataType.STRING, l.getWorld().getUID() + ";" + l.getX() + ";" + l.getY() + ";"
                + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
    }

    /** Put the player back how they were before the chase camera. Safe to call any time. */
    private void restoreCamera(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String mode = pdc.get(savedModeKey, PersistentDataType.STRING);
        String spot = pdc.get(savedSpotKey, PersistentDataType.STRING);
        pdc.remove(savedModeKey);
        pdc.remove(savedSpotKey);
        if (mode == null) return;
        if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
        try {
            p.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException e) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        if (spot != null) {
            try {
                String[] parts = spot.split(";");
                World w = Bukkit.getWorld(UUID.fromString(parts[0]));
                if (w != null) {
                    p.teleport(new Location(w, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5])));
                }
            } catch (RuntimeException ignored) {
                // bad data: leave them where they are
            }
        }
    }

    // ================================================================ events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        unfreeze(event.getPlayer()); // in case the server stopped mid-race
        restoreCamera(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        challenges.remove(id);
        challenges.values().removeIf(c -> c.from().equals(id));
        Race race = races.get(id);
        if (race != null) race.forfeit(id, "left the server");
    }

    /** Sneak is the handbrake, so don't let it kick you out of the chase camera. */
    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        if (races.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** No spectator-menu teleporting mid-race. */
    @EventHandler
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && races.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isController);
        Race race = races.get(event.getPlayer().getUniqueId());
        if (race != null) race.forfeit(event.getPlayer().getUniqueId(), "died");
    }

    // Keep the controller locked in your hand during the race.

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHotbarSwitch(PlayerItemHeldEvent event) {
        if (races.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isController(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (races.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (races.containsKey(event.getWhoClicked().getUniqueId())
                || isController(event.getCurrentItem()) || isController(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (races.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (isController(event.getItem())) event.setCancelled(true);
    }

    // ================================================================ ticking

    private void tick() {
        long now = Bukkit.getCurrentTick();
        challenges.values().removeIf(c -> {
            if (now < c.expiresAtTick()) return false;
            Player from = Bukkit.getPlayer(c.from());
            Player to = Bukkit.getPlayer(c.to());
            if (from != null) plugin.sayError(from, "Your race challenge" + (to != null ? " to " + to.getName() : "") + " expired.");
            return true;
        });
        for (Race race : new HashSet<>(races.values())) race.tick();
    }

    // ================================================================ one race

    private final class Race {
        final Racer a;
        final Racer b;
        final World world;
        final Vector start;
        final Vector forward;
        final Vector right;
        final float trackYaw;
        final double length;
        final double finishHalfWidth;
        final List<Entity> decorations = new ArrayList<>();
        Phase phase = Phase.COUNTDOWN;
        int phaseTicks = 0;
        int raceTicks = 0;
        Racer winner;

        Race(Player challenger, Player opponent) {
            FileConfiguration cfg = plugin.getConfig();
            Location o = challenger.getLocation();
            world = o.getWorld();
            trackYaw = o.getYaw();
            double rad = Math.toRadians(trackYaw);
            forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            right = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
            start = o.toVector().add(forward.clone().multiply(2.0));
            length = cfg.getDouble("race.length", 40.0);

            double laneGap = cfg.getDouble("race.lane-gap", 1.2);
            finishHalfWidth = laneGap + cfg.getDouble("race.finish-width", 3.0);
            a = new Racer(challenger, -laneGap, Material.RED_CONCRETE, NamedTextColor.RED);
            b = new Racer(opponent, laneGap, Material.BLUE_CONCRETE, NamedTextColor.BLUE);

            buildFinishLine();
            for (Racer r : List.of(a, b)) {
                Player p = r.player();
                if (p == null) continue;
                r.giveController(p);
                r.startCamera(p);
                plugin.say(p, "Race vs " + other(r).name + "! Hold your Kart Controller: W gas, S brake, A/D steer, SPACE boost.");
                if (r.tires) {
                    plugin.say(p, "Racing Tires fitted! Faster and grippier this race.");
                } else {
                    plugin.say(p, "Stone wheels this time. Craft Racing Tires (8 Rubber, from smelting sugar cane) for a faster kart.");
                }
                if (other(r).tires) plugin.say(p, other(r).name + " brought Racing Tires!");
            }
        }

        Racer other(Racer r) {
            return r == a ? b : a;
        }

        Racer racerOf(UUID id) {
            if (a.id.equals(id)) return a;
            if (b.id.equals(id)) return b;
            return null;
        }

        Location trackPoint(double along, double lane) {
            Vector v = start.clone()
                    .add(forward.clone().multiply(along))
                    .add(right.clone().multiply(lane));
            return v.toLocation(world, trackYaw, 0f);
        }

        void tick() {
            phaseTicks++;
            switch (phase) {
                case COUNTDOWN -> {
                    if (phaseTicks == 1 || phaseTicks == 21 || phaseTicks == 41) {
                        int n = 3 - (phaseTicks - 1) / 20;
                        showTitle(Component.text(n, NamedTextColor.YELLOW, TextDecoration.BOLD),
                                Component.text("Hold your Kart Controller!", NamedTextColor.GRAY));
                        playBoth(Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    } else if (phaseTicks >= 61) {
                        showTitle(Component.text("GO!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.empty());
                        playBoth(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                        phase = Phase.RACING;
                        phaseTicks = 0;
                    }
                    a.update(false);
                    b.update(false);
                }
                case RACING -> {
                    raceTicks++;
                    a.update(true);
                    b.update(true);
                    showHud();
                    boolean aDone = a.crossedFinish();
                    boolean bDone = b.crossedFinish();
                    if (aDone || bDone) {
                        finish(aDone && bDone ? (a.along() >= b.along() ? a : b) : (aDone ? a : b), null);
                    } else if (raceTicks > plugin.getConfig().getInt("race.max-seconds", 90) * 20) {
                        finish(a.along() >= b.along() ? a : b, "Time's up!");
                    }
                }
                case FINISHED -> {
                    a.update(false);
                    b.update(false);
                    if (winner != null && winner.kart != null && winner.kart.isValid() && phaseTicks % 5 == 0) {
                        world.spawnParticle(Particle.TOTEM_OF_UNDYING,
                                winner.kart.location().add(0, 0.5, 0), 10, 0.3, 0.4, 0.3, 0.2);
                    }
                    if (phaseTicks >= 80) end();
                }
            }
        }

        void finish(Racer won, String note) {
            phase = Phase.FINISHED;
            phaseTicks = 0;
            winner = won;
            Racer lost = other(won);
            Player w = won.player();
            Player l = lost.player();
            // Hand control back right away.
            for (Racer r : List.of(a, b)) r.returnController();
            if (w != null) {
                w.showTitle(Title.title(Component.text("You win!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text(note != null ? note : "You crossed the line first", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
                w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (l != null) {
                l.showTitle(Title.title(Component.text("You lost!", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text(won.name + "'s kart won" + (note != null ? " — " + note : ""), NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
                l.playSound(l.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            // Trophy cup for the winner (real wins only — not forfeits).
            if (w != null) plugin.trophies().recordWin(w);
            String msg = won.name + "'s kart won the race in " + String.format("%.1f", raceTicks / 20.0) + "s!";
            if (w != null) plugin.say(w, msg);
            if (l != null) plugin.say(l, msg);
        }

        void forfeit(UUID quitter, String reason) {
            Racer r = racerOf(quitter);
            if (r == null) return;
            if (phase != Phase.FINISHED) {
                Player op = other(r).player();
                if (op != null) plugin.say(op, r.name + " " + reason + " — you win by forfeit!");
                Player qp = r.player();
                if (qp != null) plugin.say(qp, "You forfeited the race.");
            }
            end();
        }

        void end() {
            cleanup();
            races.remove(a.id);
            races.remove(b.id);
        }

        void cleanup() {
            a.returnController();
            b.returnController();
            a.endCamera();
            b.endCamera();
            a.removeKart();
            b.removeKart();
            for (Entity e : decorations) if (e.isValid()) e.remove();
            decorations.clear();
        }

        void showHud() {
            for (Racer r : List.of(a, b)) {
                Player p = r.player();
                if (p == null) continue;
                Racer o = other(r);
                Component boost = r.boostCooldown == 0
                        ? Component.text("   BOOST READY", NamedTextColor.GOLD)
                        : Component.text("   boost " + (r.boostCooldown / 20 + 1) + "s", NamedTextColor.DARK_GRAY);
                Component hud = r.holding(p)
                        ? Component.text("You ", r.color)
                            .append(bar(r.along() / length, r.color))
                            .append(Component.text("   " + o.name + " ", o.color))
                            .append(bar(o.along() / length, o.color))
                            .append(boost)
                        : Component.text("Hold your Kart Controller to drive!", NamedTextColor.RED, TextDecoration.BOLD);
                p.sendActionBar(hud);
            }
        }

        Component bar(double fraction, NamedTextColor color) {
            int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 12);
            return Component.text("|".repeat(filled), color)
                    .append(Component.text("|".repeat(12 - filled), NamedTextColor.DARK_GRAY));
        }

        void showTitle(Component title, Component sub) {
            Title t = Title.title(title, sub,
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200)));
            for (Racer r : List.of(a, b)) {
                Player p = r.player();
                if (p != null) p.showTitle(t);
            }
        }

        void playBoth(Sound sound, float vol, float pitch) {
            for (Racer r : List.of(a, b)) {
                Player p = r.player();
                if (p != null) p.playSound(p.getLocation(), sound, vol, pitch);
            }
        }

        /** Checkered strip across the track, plus a floating FINISH sign. */
        void buildFinishLine() {
            int tiles = 12;
            double tile = (finishHalfWidth * 2) / tiles;
            Location center = trackPoint(length, 0);
            double gy = groundY(world, center.getX(), center.getZ(), center.getY());
            for (int row = 0; row < 2; row++) {
                for (int i = 0; i < tiles; i++) {
                    double lane = -finishHalfWidth + tile * (i + 0.5);
                    Location at = trackPoint(length + row * tile, lane);
                    at.setY(groundY(world, at.getX(), at.getZ(), gy) + 0.01);
                    Material m = (i + row) % 2 == 0 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE;
                    float t = (float) tile;
                    decorations.add(spawnBlock(at, m,
                            new Vector3f(-t / 2, 0, -t / 2), new Vector3f(t, 0.02f, t), 0));
                }
            }
            Location sign = center.clone();
            sign.setY(gy + 2.2);
            decorations.add(world.spawn(sign, TextDisplay.class, d -> {
                d.text(Component.text("FINISH", NamedTextColor.WHITE, TextDecoration.BOLD));
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(2f, 2f, 2f), new Quaternionf()));
            }));
        }

        // ------------------------------------------------------------ a racer + their kart

        private final class Racer {
            final UUID id;
            final String name;
            final NamedTextColor color;
            final double k;
            /** Racing tires fitted (crafted Tires item): faster and grippier. */
            final boolean tires;
            /** Kart position on the ground plane, its heading and speed. */
            final Vector pos;
            float heading;
            float pitch;
            double speed;
            double y;
            int boostTicks;
            int boostCooldown;
            boolean wasJumping;
            int bumpCooldown;
            Mannequin driver;
            Kart kart;
            double steerInput;
            // Controller bookkeeping
            int controllerSlot = -1;
            ItemStack savedItem;
            boolean hasController;
            // Chase camera
            ItemDisplay camera;
            float cameraYaw;
            boolean cameraOn;

            Racer(Player player, double lane, Material paint, NamedTextColor color) {
                this.id = player.getUniqueId();
                this.name = player.getName();
                this.color = color;
                this.tires = plugin.items().useTires(player);

                double driverSize = plugin.getConfig().getDouble("kart.driver-size", 0.35);
                this.k = Math.max(0.5, Math.min(2.5, plugin.getConfig().getDouble("kart.scale", 1.0)));

                Location at = trackPoint(0, lane);
                pos = at.toVector().setY(0);
                heading = trackYaw;
                y = groundY(world, at.getX(), at.getZ(), at.getY());
                at.setY(y + 0.18 * k);

                // The kart: a detailed model with spinning wheels (see Kart). Number 1 is the challenger.
                kart = new Kart(at, k, paint, tires, lane < 0 ? 1 : 2, color);

                driver = world.spawn(at, Mannequin.class, m -> {
                    m.setPersistent(false);
                    m.setInvulnerable(true);
                    m.setSilent(true);
                    m.setImmovable(true);
                    m.setCollidable(false);
                    m.getPersistentDataContainer().set(plugin.driverKey(), PersistentDataType.STRING, id.toString());
                    AttributeInstance scale = m.getAttribute(Attribute.SCALE);
                    if (scale != null) scale.setBaseValue(driverSize * k);
                    m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
                    m.customName(Component.text(player.getName(), color));
                    m.setCustomNameVisible(true);
                    m.setDescription(null);
                    m.setRotation(heading, 0f);
                });
                kart.seat().addPassenger(driver);
            }

            Player player() {
                return Bukkit.getPlayer(id);
            }

            Vector3f v(double x, double y, double z) {
                return new Vector3f((float) (x * k), (float) (y * k), (float) (z * k));
            }


            // ---- controller

            void giveController(Player p) {
                PlayerInventory inv = p.getInventory();
                controllerSlot = inv.getHeldItemSlot();
                ItemStack current = inv.getItem(controllerSlot);
                savedItem = current == null || current.isEmpty() ? null : current.clone();
                inv.setItem(controllerSlot, makeController(color));
                hasController = true;
                freeze(p);
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1.2f);
            }

            void returnController() {
                if (!hasController) return;
                hasController = false;
                Player p = player();
                if (p == null) return; // they're offline; it's cleaned up on their next join
                PlayerInventory inv = p.getInventory();
                if (controllerSlot >= 0 && isController(inv.getItem(controllerSlot))) {
                    inv.setItem(controllerSlot, savedItem);
                    savedItem = null;
                }
                unfreeze(p); // also clears any stray controllers
                if (savedItem != null) {
                    for (ItemStack left : inv.addItem(savedItem).values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                    }
                    savedItem = null;
                }
            }

            boolean holding(Player p) {
                if (p == null) return false;
                // In the chase camera the hotbar is hidden, but the controller stays locked in the hand slot.
                return cameraOn || isController(p.getInventory().getItemInMainHand());
            }

            // ---- chase camera

            void startCamera(Player p) {
                if (!plugin.getConfig().getBoolean("race.camera.enabled", true)) return;
                cameraYaw = heading;
                Location at = cameraSpot();
                // An empty item display: invisible, no hitbox, and the client glides it smoothly between spots.
                camera = world.spawn(at, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setTeleportDuration(3);
                });
                saveForCamera(p);
                p.setGameMode(GameMode.SPECTATOR);
                cameraOn = true;
                // The client needs a moment to know about the new entity before it can look through it;
                // update() keeps re-attaching until it sticks.
            }

            void endCamera() {
                if (camera != null && camera.isValid()) camera.remove();
                camera = null;
                if (!cameraOn) return;
                cameraOn = false;
                Player p = player();
                if (p != null) restoreCamera(p); // offline: restored on their next join
            }

            /** Behind and above the kart, pulled in closer if a block is in the way. */
            Location cameraSpot() {
                FileConfiguration cfg = plugin.getConfig();
                double distance = cfg.getDouble("race.camera.distance", 3.2) * Math.max(0.8, k);
                double height = cfg.getDouble("race.camera.height", 1.6) * Math.max(0.8, k);
                float pitch = (float) cfg.getDouble("race.camera.pitch", 22.0);
                double rad = Math.toRadians(cameraYaw);
                Vector back = new Vector(Math.sin(rad), 0, -Math.cos(rad));
                Vector kart = new Vector(pos.getX(), y + 0.4 * k, pos.getZ());
                Vector want = kart.clone().add(back.clone().multiply(distance)).add(new Vector(0, height, 0));
                // March from the kart toward the wanted spot and stop before solid blocks.
                Vector step = want.clone().subtract(kart);
                double len = step.length();
                step.normalize().multiply(0.25);
                Vector cam = kart.clone();
                for (double d = 0; d < len; d += 0.25) {
                    Vector next = cam.clone().add(step);
                    if (!world.getBlockAt(next.getBlockX(), next.getBlockY(), next.getBlockZ()).isPassable()) break;
                    cam = next;
                }
                return cam.toLocation(world, cameraYaw, pitch);
            }

            void updateCamera() {
                if (!cameraOn || camera == null || !camera.isValid()) return;
                float follow = (float) plugin.getConfig().getDouble("race.camera.turn-smoothing", 0.25);
                cameraYaw = lerpAngle(cameraYaw, heading, follow);
                camera.teleport(cameraSpot());
                Player p = player();
                if (p != null && (phaseTicks % 5 == 0 || p.getSpectatorTarget() == null)
                        && !camera.equals(p.getSpectatorTarget())) {
                    if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
                    p.setSpectatorTarget(camera);
                }
            }

            // ---- driving

            double along() {
                return pos.clone().subtract(start.clone().setY(0)).dot(forward);
            }

            double lateral() {
                return pos.clone().subtract(start.clone().setY(0)).dot(right);
            }

            boolean crossedFinish() {
                return along() >= length && Math.abs(lateral()) <= finishHalfWidth;
            }

            /** @param live true while racing: read the controller; false: just idle in place */
            void update(boolean live) {
                if (kart == null || !kart.isValid()) return;
                FileConfiguration cfg = plugin.getConfig();
                double accel = cfg.getDouble("race.acceleration", 0.02);
                double topSpeed = cfg.getDouble("race.top-speed", 0.45);
                double turnRate = cfg.getDouble("race.turn-speed", 6.0);
                if (tires) {
                    topSpeed *= cfg.getDouble("race.tires-top-speed-bonus", 1.15);
                    turnRate *= cfg.getDouble("race.tires-grip-bonus", 1.35);
                    accel *= 1.2;
                }

                boolean gas = false, brake = false, left = false, rightKey = false, jump = false, handbrake = false;
                Player p = player();
                if (live && holding(p)) {
                    Input in = p.getCurrentInput();
                    gas = in.isForward();
                    brake = in.isBackward();
                    left = in.isLeft();
                    rightKey = in.isRight();
                    jump = in.isJump();
                    handbrake = in.isSneak();
                }

                // Boost on a fresh SPACE press.
                if (boostCooldown > 0) boostCooldown--;
                if (jump && !wasJumping && boostCooldown == 0 && live) {
                    boostTicks = cfg.getInt("race.boost-ticks", 25);
                    boostCooldown = cfg.getInt("race.boost-cooldown-ticks", 80);
                    world.playSound(kart.location(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f * kartVolume(), 1.3f);
                    if (driver != null && driver.isValid()) driver.swingMainHand();
                }
                wasJumping = jump;
                double max = topSpeed;
                if (boostTicks > 0) {
                    boostTicks--;
                    max += cfg.getDouble("race.boost-speed", 0.30);
                    speed += accel * 2;
                }

                // Throttle / brake.
                if (gas) speed += accel;
                else if (brake) speed -= speed > 0 ? accel * 2.5 : accel * 0.7;
                else speed *= 0.96;                 // coasting
                if (handbrake) speed *= 0.85;
                speed = Math.max(-0.15, Math.min(max, speed));
                if (Math.abs(speed) < 0.002) speed = 0;

                // Steering: turns faster the faster you go, flips in reverse like a real car.
                double grip = Math.min(1.0, Math.abs(speed) / (tires ? 0.08 : 0.15));
                double turn = turnRate * grip * (handbrake ? 1.6 : 1.0) * Math.signum(speed);
                if (left) heading -= (float) turn;
                if (rightKey) heading += (float) turn;

                // Move, bumping off walls.
                double rad = Math.toRadians(heading);
                Vector dir = new Vector(-Math.sin(rad), 0, Math.cos(rad));
                Vector before = pos.clone();
                Vector next = pos.clone().add(dir.clone().multiply(speed));
                double gy = groundY(world, next.getX(), next.getZ(), y);
                if (bumpCooldown > 0) bumpCooldown--;
                if (gy - y > 1.05) {
                    // Wall: bounce back a little.
                    if (bumpCooldown == 0 && Math.abs(speed) > 0.08) {
                        world.playSound(kart.location(), Sound.ENTITY_IRON_GOLEM_HURT, 0.4f * kartVolume(), 1.8f);
                        bumpCooldown = 10;
                    }
                    speed = -speed * 0.3;
                } else {
                    pos.copy(next);
                    y += (gy - y) * 0.5;
                }

                // Slope: tilt the kart nose-up / nose-down to match the ground under its wheels.
                double wheelbase = 0.36 * k;
                double frontY = groundY(world, pos.getX() + dir.getX() * wheelbase, pos.getZ() + dir.getZ() * wheelbase, y);
                double backY = groundY(world, pos.getX() - dir.getX() * wheelbase, pos.getZ() - dir.getZ() * wheelbase, y);
                double slope = Math.max(-1.0, Math.min(1.0, frontY - backY));
                float targetPitch = (float) -Math.toDegrees(Math.atan2(slope, wheelbase * 2));
                pitch += (targetPitch - pitch) * 0.3f;

                // A little engine vibration and bump over rough ground.
                double vibrate = Math.abs(speed) > 0.02
                        ? (Math.sin(raceTicks * 2.3) * 0.004 + Math.abs(Math.sin(raceTicks * 0.9)) * 0.008) * k
                        : Math.sin(phaseTicks * 3.1) * 0.002 * k;   // idling shake
                double moved = pos.distance(before) * Math.signum(speed);
                steerInput = left == rightKey ? 0 : (left ? 1 : -1);
                Location at = new Location(world, pos.getX(), y + Kart.RIDE_HEIGHT * k + vibrate, pos.getZ(), heading, pitch);
                kart.move(at, moved, steerInput, driver);
                if (driver != null && driver.isValid()) driver.setBodyYaw(heading);
                updateCamera();

                // Exhaust + engine noise.
                Vector rightSide = new Vector(-dir.getZ(), 0, dir.getX()); // driver's right
                Location exhaust = at.clone().subtract(dir.clone().multiply(0.56 * k)).add(rightSide.multiply(0.31 * k));
                exhaust.setY(at.getY() + 0.01 * k);
                if (phaseTicks % 2 == 0) world.spawnParticle(Particle.SMOKE, exhaust, 1, 0.03, 0.02, 0.03, 0.01);
                if (boostTicks > 0) world.spawnParticle(Particle.FLAME, exhaust, 3, 0.04, 0.03, 0.04, 0.01);
                if (handbrake && Math.abs(speed) > 0.1) {
                    world.spawnParticle(Particle.CLOUD, exhaust, 1, 0.1, 0.0, 0.1, 0.0);
                }
                // Engine hum: the minecart sound is long, so play it rarely to keep it from stacking up.
                float engine = (float) cfg.getDouble("race.engine-volume", 0.03);
                if (live && engine > 0 && phaseTicks % 30 == 0 && Math.abs(speed) > 0.05) {
                    world.playSound(at, Sound.ENTITY_MINECART_INSIDE, engine, 1.2f + (float) Math.abs(speed) * 2f);
                }
            }

            void removeKart() {
                if (driver != null && driver.isValid()) driver.remove();
                if (kart != null) kart.remove();
                if (camera != null && camera.isValid()) camera.remove();
                driver = null;
                kart = null;
            }
        }
    }

    // ================================================================ helpers

    private BlockDisplay spawnBlock(Location at, Material material, Vector3f translation, Vector3f size, int teleportTicks) {
        return at.getWorld().spawn(at, BlockDisplay.class, d -> {
            d.setBlock(material.createBlockData());
            d.setTransformation(new Transformation(translation, new Quaternionf(), size, new Quaternionf()));
            d.setTeleportDuration(teleportTicks);
            d.setPersistent(false);
        });
    }

    private static float lerpAngle(float from, float to, float t) {
        float diff = ((to - from) % 360f + 540f) % 360f - 180f;
        return from + diff * t;
    }

    /** Volume multiplier for kart boost / bump sounds (0 = silent). */
    private float kartVolume() {
        return (float) Math.max(0, plugin.getConfig().getDouble("race.kart-volume", 0.35));
    }

    /** Top of the ground near (x, z), searching a few blocks around the given height. */
    private static double groundY(World world, double x, double z, double nearY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(nearY) + 2;
        for (int by = top; by > top - 8 && by > world.getMinHeight(); by--) {
            Block block = world.getBlockAt(bx, by, bz);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return nearY;
    }

    public boolean isRacing(Player player) {
        return races.containsKey(player.getUniqueId());
    }

    /** Names of players who challenged this player (for tab-complete). */
    public Set<String> challengersOf(Player player) {
        Set<String> names = new HashSet<>();
        Challenge c = challenges.get(player.getUniqueId());
        if (c != null) {
            Player from = Bukkit.getPlayer(c.from());
            if (from != null) names.add(from.getName());
        }
        return names;
    }
}
