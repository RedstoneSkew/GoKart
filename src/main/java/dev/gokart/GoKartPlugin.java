package dev.gokart;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** GoKart: challenge a player with /kart duel and race detailed go-karts with a controller and chase camera. */
public final class GoKartPlugin extends JavaPlugin implements Listener {

    public static final Component PREFIX = Component.text("GoKart ", NamedTextColor.GOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private NamespacedKey driverKey;
    private CustomItems items;
    private RaceManager races;
    private Trophies trophies;
    private ResourcePackHost pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        driverKey = new NamespacedKey(this, "driver");
        items = new CustomItems(this);
        items.discoverForOnline();
        trophies = new Trophies(this);
        pack = new ResourcePackHost(this);
        races = new RaceManager(this);
        getServer().getPluginManager().registerEvents(this, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("kart", "Go-kart races: /kart duel <player>",
                        List.of("gokart"), new KartCommand(this)));

        getLogger().info("GoKart " + getPluginMeta().getVersion() + " by Redstoneskew is ready to race!");

        // Drivers are never saved, but clear any left over from a crash.
        for (World w : getServer().getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (isDriver(m)) m.remove();
            }
        }
    }

    @Override
    public void onDisable() {
        if (races != null) races.stopAll();
        if (items != null) items.removeRecipes();
        if (pack != null) pack.stop();
    }

    public NamespacedKey driverKey() {
        return driverKey;
    }

    public CustomItems items() {
        return items;
    }

    public Trophies trophies() {
        return trophies;
    }

    public RaceManager races() {
        return races;
    }

    private boolean isDriver(Entity e) {
        return e.getPersistentDataContainer().has(driverKey, PersistentDataType.STRING);
    }

    public void say(CommandSender to, String message) {
        to.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GRAY)));
    }

    public void sayError(CommandSender to, String message) {
        to.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }

    // Kart drivers can't be hurt or interacted with.

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (isDriver(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isDriver(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (isDriver(event.getRightClicked())) event.setCancelled(true);
    }
}
