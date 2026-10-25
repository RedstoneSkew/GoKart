package dev.gokart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Trophy cups. Your first race win gives you a Copper Cup, and every 5 wins it upgrades:
 * Copper → Iron → Gold → Emerald → Diamond → Netherite → Champion.
 * Right-click a block with it to put it on display as a 3D trophy; sneak + right-click the
 * trophy to pick it back up. Wins are saved in plugins/GoKart/wins.yml.
 */
public final class Trophies implements Listener {

    public enum Tier {
        COPPER("Copper Cup", Material.WAXED_COPPER_BLOCK, "copper_ingot", TextColor.color(0xD9804F)),
        IRON("Iron Cup", Material.IRON_BLOCK, "iron_ingot", TextColor.color(0xD8D8D8)),
        GOLD("Gold Cup", Material.GOLD_BLOCK, "gold_ingot", TextColor.color(0xFFD83D)),
        EMERALD("Emerald Cup", Material.EMERALD_BLOCK, "emerald", TextColor.color(0x3DD86B)),
        DIAMOND("Diamond Cup", Material.DIAMOND_BLOCK, "diamond", TextColor.color(0x5CE1E6)),
        NETHERITE("Netherite Cup", Material.NETHERITE_BLOCK, "netherite_ingot", TextColor.color(0x8B6F7A)),
        CHAMPION("Champion Cup", Material.GOLD_BLOCK, "nether_star", TextColor.color(0xFF9F1C));

        public final String label;
        public final Material block;
        public final String model;
        public final TextColor color;

        Tier(String label, Material block, String model, TextColor color) {
            this.label = label;
            this.block = block;
            this.model = model;
            this.color = color;
        }

        /** 1-4 wins Copper, 5-9 Iron, 10-14 Gold ... (with 5 wins per upgrade). */
        static Tier forWins(int wins, int perTier) {
            int index = Math.max(0, wins) / perTier;
            return values()[Math.min(index, values().length - 1)];
        }
    }

    private final GoKartPlugin plugin;
    private final NamespacedKey ownerKey;    // on the item and every placed part: owner UUID
    private final NamespacedKey trophyIdKey; // on every placed part: which trophy it belongs to
    private final NamespacedKey tierKey;     // on placed parts: tier when built
    private final File file;
    private final YamlConfiguration data;

    Trophies(GoKartPlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "trophy_owner");
        this.trophyIdKey = new NamespacedKey(plugin, "trophy_id");
        this.tierKey = new NamespacedKey(plugin, "trophy_tier");
        this.file = new File(plugin.getDataFolder(), "wins.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private int perTier() {
        return Math.max(1, plugin.getConfig().getInt("trophy.wins-per-upgrade", 5));
    }

    // ================================================================ wins

    public int wins(UUID id) {
        return data.getInt("players." + id + ".wins", 0);
    }

    private String nameOf(UUID id) {
        return data.getString("players." + id + ".name", "Someone");
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save wins.yml: " + e.getMessage());
        }
    }

    /** Called when a player wins a race (not by forfeit). */
    public void recordWin(Player p) {
        String path = "players." + p.getUniqueId();
        int wins = data.getInt(path + ".wins", 0) + 1;
        data.set(path + ".wins", wins);
        data.set(path + ".name", p.getName());
        save();

        Tier tier = Tier.forWins(wins, perTier());
        Tier before = wins > 1 ? Tier.forWins(wins - 1, perTier()) : null;

        // Upgrade the cup they're carrying, or any they've put on display; otherwise hand out a new one.
        boolean found = upgradeCarried(p, wins);
        found |= upgradePlaced(p.getUniqueId(), wins);
        if (!found) give(p, item(p.getUniqueId(), p.getName(), wins));

        if (before == null) {
            announce(p, Component.text("You won a ", NamedTextColor.GOLD)
                    .append(Component.text(tier.label, tier.color, TextDecoration.BOLD))
                    .append(Component.text("! Win " + (perTier() - 1) + " more to upgrade it.", NamedTextColor.GOLD)));
        } else if (before != tier) {
            p.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("Trophy upgraded!", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text(before.label + " → " + tier.label, tier.color),
                    net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            announce(p, Component.text("Your trophy is now a ", NamedTextColor.GOLD)
                    .append(Component.text(tier.label, tier.color, TextDecoration.BOLD))
                    .append(Component.text(" (" + wins + " wins)!", NamedTextColor.GOLD)));
        } else {
            int toGo = nextUpgradeAt(wins) - wins;
            announce(p, Component.text("Win #" + wins + " added to your " + tier.label
                    + (toGo > 0 ? " — " + toGo + " more to upgrade." : "."), NamedTextColor.YELLOW));
        }
    }

    private int nextUpgradeAt(int wins) {
        Tier tier = Tier.forWins(wins, perTier());
        if (tier.ordinal() == Tier.values().length - 1) return -1;
        return (tier.ordinal() + 1) * perTier();
    }

    private void announce(Player p, Component msg) {
        p.sendMessage(GoKartPlugin.PREFIX.append(msg));
    }

    public void showWins(org.bukkit.command.CommandSender to, UUID id, String name) {
        int wins = wins(id);
        if (wins == 0) {
            plugin.say(to, name + " hasn't won a race yet.");
            return;
        }
        Tier tier = Tier.forWins(wins, perTier());
        int next = nextUpgradeAt(wins);
        to.sendMessage(GoKartPlugin.PREFIX
                .append(Component.text(name + ": " + wins + " win" + (wins == 1 ? "" : "s") + " — ", NamedTextColor.GRAY))
                .append(Component.text(tier.label, tier.color, TextDecoration.BOLD))
                .append(Component.text(next > 0 ? " (next upgrade at " + next + ")" : " (max tier!)", NamedTextColor.GRAY)));
    }

    public void showTop(org.bukkit.command.CommandSender to) {
        var section = data.getConfigurationSection("players");
        if (section == null || section.getKeys(false).isEmpty()) {
            plugin.say(to, "Nobody has won a race yet.");
            return;
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>();
        for (String key : section.getKeys(false)) rows.add(Map.entry(key, section.getInt(key + ".wins")));
        rows.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        to.sendMessage(Component.text("—— Top Racers ——", NamedTextColor.GOLD));
        for (int i = 0; i < Math.min(10, rows.size()); i++) {
            var row = rows.get(i);
            Tier tier = Tier.forWins(row.getValue(), perTier());
            to.sendMessage(Component.text((i + 1) + ". ", NamedTextColor.YELLOW)
                    .append(Component.text(section.getString(row.getKey() + ".name", "?") + "  ", NamedTextColor.WHITE))
                    .append(Component.text(row.getValue() + " wins  ", NamedTextColor.GRAY))
                    .append(Component.text(tier.label, tier.color)));
        }
    }

    // ================================================================ the item

    public ItemStack item(UUID owner, String ownerName, int wins) {
        Tier tier = Tier.forWins(wins, perTier());
        int next = nextUpgradeAt(wins);
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> {
            meta.displayName(Component.text(tier.label, tier.color, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(plain("Go-kart racing trophy", NamedTextColor.GOLD));
            lore.add(plain("Won by " + ownerName, NamedTextColor.WHITE));
            lore.add(plain("Race wins: " + wins, NamedTextColor.GRAY));
            lore.add(plain(next > 0 ? "Upgrades at " + next + " wins" : "Highest tier!", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(plain("Right-click a block to put it on display.", NamedTextColor.YELLOW));
            meta.lore(lore);
            meta.setItemModel(NamespacedKey.minecraft(tier.model));
            meta.setMaxStackSize(1);
            meta.setEnchantmentGlintOverride(tier.ordinal() >= Tier.DIAMOND.ordinal());
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
            // Shared custom-item tag, so the trophy (paper underneath) can't be used in paper recipes.
            meta.getPersistentDataContainer().set(plugin.items().typeKey(), PersistentDataType.STRING, "trophy");
        });
        // The GoKart resource pack swaps this item's picture for a 3D trophy cup (see ResourcePackHost).
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addString("gokart_trophy").build());
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private UUID ownerOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean upgradeCarried(Player p, int wins) {
        PlayerInventory inv = p.getInventory();
        boolean found = false;
        for (int i = 0; i < inv.getSize(); i++) {
            if (p.getUniqueId().equals(ownerOf(inv.getItem(i)))) {
                inv.setItem(i, item(p.getUniqueId(), p.getName(), wins));
                found = true;
            }
        }
        return found;
    }

    private static void give(Player p, ItemStack item) {
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    // ================================================================ placed trophies

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack held = event.getItem();
        UUID owner = ownerOf(held);
        if (owner == null) return;
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Block spot = clicked.getRelative(event.getBlockFace() == null ? BlockFace.UP : event.getBlockFace());
        if (!spot.isPassable() || spot.isLiquid()) {
            plugin.sayError(event.getPlayer(), "There's no room for the trophy there.");
            return;
        }
        Location at = spot.getLocation().add(0.5, 0, 0.5);
        // Sit on top of slabs / carpet etc.
        if (event.getBlockFace() == BlockFace.UP) at.setY(clicked.getBoundingBox().getMaxY());
        // Face the player.
        Location eye = event.getPlayer().getLocation();
        at.setYaw((float) Math.toDegrees(Math.atan2(-(eye.getX() - at.getX()), eye.getZ() - at.getZ())));
        build(at, owner, wins(owner), nameOf(owner));
        held.setAmount(held.getAmount() - 1);
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1f, 1.2f);
    }

    /** Right-click: info. Sneak + right-click (owner): pick it up. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickTrophy(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction hitbox)) return;
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String id = pdc.get(trophyIdKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        UUID owner = UUID.fromString(pdc.get(ownerKey, PersistentDataType.STRING));
        Player p = event.getPlayer();
        if (p.isSneaking() && (p.getUniqueId().equals(owner) || p.hasPermission("gokart.admin"))) {
            Location at = hitbox.getLocation();
            removeTrophy(hitbox.getWorld(), at, id);
            give(p, item(owner, nameOf(owner), wins(owner)));
            p.playSound(at, Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            return;
        }
        showWins(p, owner, nameOf(owner));
        if (p.getUniqueId().equals(owner)) plugin.say(p, "Sneak + right-click to pick it up.");
    }

    /** When a chunk with a trophy loads, rebuild it if its owner has upgraded since. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (!(e instanceof Interaction hitbox)) continue;
            refreshIfStale(hitbox);
        }
    }

    private boolean upgradePlaced(UUID owner, int wins) {
        boolean found = false;
        for (World w : Bukkit.getWorlds()) {
            for (Interaction hitbox : w.getEntitiesByClass(Interaction.class)) {
                String raw = hitbox.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (raw != null && raw.equals(owner.toString())) {
                    refreshIfStale(hitbox);
                    found = true;
                }
            }
        }
        return found;
    }

    private void refreshIfStale(Interaction hitbox) {
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        String id = pdc.get(trophyIdKey, PersistentDataType.STRING);
        String rawOwner = pdc.get(ownerKey, PersistentDataType.STRING);
        Integer builtWins = pdc.get(tierKey, PersistentDataType.INTEGER);
        if (id == null || rawOwner == null) return;
        UUID owner = UUID.fromString(rawOwner);
        int wins = wins(owner);
        if (builtWins != null && builtWins == wins) return;
        Location at = hitbox.getLocation();
        at.setYaw(pdc.getOrDefault(new NamespacedKey(plugin, "trophy_yaw"), PersistentDataType.FLOAT, 0f));
        removeTrophy(hitbox.getWorld(), at, id);
        build(at, owner, wins, nameOf(owner));
        hitbox.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 0.6, 0), 30, 0.2, 0.3, 0.2, 0.2);
    }

    private void removeTrophy(World world, Location at, String id) {
        for (Entity e : world.getNearbyEntities(at, 1.5, 2.0, 1.5)) {
            if (id.equals(e.getPersistentDataContainer().get(trophyIdKey, PersistentDataType.STRING))) e.remove();
        }
    }

    // ================================================================ the 3D trophy

    /** A cup on a plinth, with handles and a name plaque, built from display blocks. */
    private void build(Location at, UUID owner, int wins, String ownerName) {
        Tier tier = Tier.forWins(Math.max(1, wins), perTier());
        String id = UUID.randomUUID().toString();
        float scale = (float) plugin.getConfig().getDouble("trophy.display-scale", 1.0);
        World w = at.getWorld();
        Material metal = tier.block;
        Material dark = Material.POLISHED_BLACKSTONE;
        Material plaque = Material.SMOOTH_QUARTZ;
        Quaternionf yaw = new Quaternionf().rotateY((float) Math.toRadians(-at.getYaw()));
        Location origin = at.clone();
        origin.setYaw(0);
        origin.setPitch(0);

        Consumer<Entity> tag = e -> {
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(trophyIdKey, PersistentDataType.STRING, id);
            pdc.set(ownerKey, PersistentDataType.STRING, owner.toString());
        };

        // Plinth
        box(w, origin, dark, 0, 0.00, 0, 0.50, 0.10, 0.50, yaw, scale, tag);
        box(w, origin, dark, 0, 0.10, 0, 0.40, 0.08, 0.40, yaw, scale, tag);
        box(w, origin, metal, 0, 0.18, 0, 0.30, 0.02, 0.30, yaw, scale, tag);
        // Name plaque on the front of the plinth
        box(w, origin, plaque, 0, 0.03, 0.252, 0.30, 0.05, 0.004, yaw, scale, tag);
        // Stem with a knot in the middle
        box(w, origin, metal, 0, 0.20, 0, 0.08, 0.22, 0.08, yaw, scale, tag);
        box(w, origin, metal, 0, 0.28, 0, 0.13, 0.05, 0.13, yaw, scale, tag);
        // Bowl: widening rings, then a dark inner hollow
        box(w, origin, metal, 0, 0.42, 0, 0.16, 0.05, 0.16, yaw, scale, tag);
        box(w, origin, metal, 0, 0.47, 0, 0.24, 0.06, 0.24, yaw, scale, tag);
        box(w, origin, metal, 0, 0.53, 0, 0.32, 0.10, 0.32, yaw, scale, tag);
        box(w, origin, metal, 0, 0.63, 0, 0.36, 0.05, 0.36, yaw, scale, tag);
        box(w, origin, dark, 0, 0.676, 0, 0.28, 0.006, 0.28, yaw, scale, tag);
        // Handles
        for (int side : new int[]{1, -1}) {
            box(w, origin, metal, side * 0.215, 0.62, 0, 0.07, 0.03, 0.04, yaw, scale, tag);
            box(w, origin, metal, side * 0.245, 0.48, 0, 0.03, 0.16, 0.04, yaw, scale, tag);
            box(w, origin, metal, side * 0.20, 0.47, 0, 0.07, 0.03, 0.04, yaw, scale, tag);
        }
        // Champion & netherite cups get a star on top
        if (tier.ordinal() >= Tier.NETHERITE.ordinal()) {
            Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(45)).rotateZ((float) Math.toRadians(45));
            star(w, origin, tier == Tier.CHAMPION ? Material.DIAMOND_BLOCK : Material.GOLD_BLOCK,
                    0.74, 0.09, yaw, tilt, scale, tag);
        }

        // Floating label
        Location label = origin.clone().add(0, 0.95 * scale, 0);
        w.spawn(label, TextDisplay.class, t -> {
            t.text(Component.text(ownerName + "'s ", NamedTextColor.WHITE)
                    .append(Component.text(tier.label, tier.color, TextDecoration.BOLD))
                    .appendNewline()
                    .append(Component.text(wins + " race win" + (wins == 1 ? "" : "s"), NamedTextColor.GRAY)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(80, 0, 0, 0));
            float s = 0.5f * scale;
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            tag.accept(t);
        });

        // Click box
        w.spawn(origin, Interaction.class, i -> {
            i.setInteractionWidth(0.55f * scale);
            i.setInteractionHeight(0.8f * scale);
            i.setResponsive(true);
            tag.accept(i);
            i.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, wins);
            i.getPersistentDataContainer().set(new NamespacedKey(plugin, "trophy_yaw"), PersistentDataType.FLOAT, at.getYaw());
        });
    }

    /** One block piece: a box of size (sx,sy,sz) whose bottom-centre sits at (x,y,z), turned to face {@code yaw}. */
    private void box(World w, Location origin, Material m, double x, double y, double z,
                     double sx, double sy, double sz, Quaternionf yaw, float scale, Consumer<Entity> tag) {
        place(w, origin, m, new Vector3f((float) x, (float) (y + sy / 2), (float) z),
                new Vector3f((float) sx, (float) sy, (float) sz), yaw, new Quaternionf(), scale, false, tag);
    }

    /** Glowing tilted cube on top of the best cups. */
    private void star(World w, Location origin, Material m, double y, double size, Quaternionf yaw,
                      Quaternionf tilt, float scale, Consumer<Entity> tag) {
        place(w, origin, m, new Vector3f(0, (float) (y + size / 2), 0),
                new Vector3f((float) size, (float) size, (float) size), yaw, tilt, scale, true, tag);
    }

    private void place(World w, Location origin, Material m, Vector3f center, Vector3f size,
                       Quaternionf yaw, Quaternionf extra, float scale, boolean glow, Consumer<Entity> tag) {
        center.mul(scale);
        size.mul(scale);
        Vector3f placed = yaw.transform(new Vector3f(center), new Vector3f());     // position turns with the trophy
        Quaternionf rot = new Quaternionf(yaw).mul(extra);                       // piece turns, plus any tilt
        Vector3f translation = new Vector3f(placed).sub(rot.transform(new Vector3f(size).mul(0.5f), new Vector3f()));
        w.spawn(origin, BlockDisplay.class, d -> {
            d.setBlock(m.createBlockData());
            d.setTransformation(new Transformation(translation, rot, new Vector3f(size), new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15)); // trophies always look shiny
            if (glow) {
                d.setGlowing(true);
                d.setGlowColorOverride(m == Material.DIAMOND_BLOCK ? Color.AQUA : Color.YELLOW);
            }
            tag.accept(d);
        });
    }
}
