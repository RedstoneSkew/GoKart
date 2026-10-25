package dev.gokart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Rubber (smelt Sugar Cane in a furnace -> 1 Rubber) and Tires (8 Rubber in a ring on a crafting table).
 * Having Tires in your inventory when a race starts puts racing tires on your go-kart.
 */
public final class CustomItems implements Listener {

    public static final String RUBBER = "rubber";
    public static final String TIRES = "tires";

    private final GoKartPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey rubberRecipeKey;
    private final NamespacedKey tiresRecipeKey;

    CustomItems(GoKartPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "item_type");
        this.rubberRecipeKey = new NamespacedKey(plugin, "rubber_from_sugar_cane");
        this.tiresRecipeKey = new NamespacedKey(plugin, "tires");
        registerRecipes();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------------------------------------------------------------- the items

    public ItemStack rubber(int amount) {
        // Built on paper (so it can't be used as vanilla dye) but drawn with the black dye texture.
        ItemStack item = new ItemStack(Material.PAPER, amount);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Rubber", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Smelted from sugar cane.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Craft 8 in a ring to make Tires.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setItemModel(NamespacedKey.minecraft("black_dye"));
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, RUBBER);
        });
        return item;
    }

    public ItemStack tires(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Racing Tires", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Keep these in your inventory when a", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("/kart duel starts: your go-kart gets", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("more top speed and much better grip.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text(plugin.getConfig().getBoolean("race.tires-used-up", true)
                            ? "One set wears out each race." : "They never wear out.", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            meta.setItemModel(NamespacedKey.minecraft("music_disc_11")); // a round black disc
            meta.setMaxStackSize(16);
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, TIRES);
        });
        return item;
    }

    public ItemStack create(String type, int amount) {
        return switch (type) {
            case RUBBER -> rubber(amount);
            case TIRES -> tires(amount);
            default -> null;
        };
    }

    public String typeOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    /** Tag every GoKart custom item carries. */
    public NamespacedKey typeKey() {
        return typeKey;
    }

    public boolean isTires(ItemStack item) {
        return TIRES.equals(typeOf(item));
    }

    /**
     * Does this player have Tires? If the config says tires wear out, one is used up.
     * @return true if their kart should get racing tires
     */
    public boolean useTires(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!isTires(item)) continue;
            if (plugin.getConfig().getBoolean("race.tires-used-up", true)) {
                item.setAmount(item.getAmount() - 1);
                inv.setItem(i, item.getAmount() <= 0 ? null : item);
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- recipes

    private void registerRecipes() {
        removeRecipes(); // in case of /reload

        FurnaceRecipe rubberRecipe = new FurnaceRecipe(rubberRecipeKey, rubber(1), Material.SUGAR_CANE, 0.1f, 200);
        Bukkit.addRecipe(rubberRecipe, true);

        ShapedRecipe tiresRecipe = new ShapedRecipe(tiresRecipeKey, tires(1));
        tiresRecipe.shape("RRR", "R R", "RRR");
        tiresRecipe.setIngredient('R', new RecipeChoice.ExactChoice(rubber(1)));
        Bukkit.addRecipe(tiresRecipe, true);
    }

    void removeRecipes() {
        Bukkit.removeRecipe(rubberRecipeKey);
        Bukkit.removeRecipe(tiresRecipeKey);
    }

    /** Show both recipes in everyone's recipe book. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipes(List.of(rubberRecipeKey, tiresRecipeKey));
    }

    void discoverForOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipes(List.of(rubberRecipeKey, tiresRecipeKey));
    }

    /** Rubber and Tires are made of paper underneath: stop them working in vanilla paper recipes. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        boolean usesOurs = false;
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (typeOf(item) != null) {
                usesOurs = true;
                break;
            }
        }
        if (!usesOurs) return;
        Recipe recipe = event.getRecipe();
        boolean ourRecipe = recipe instanceof Keyed keyed && keyed.getKey().equals(tiresRecipeKey);
        if (!ourRecipe) event.getInventory().setResult(null);
    }
}
