package dev.gokart;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Sends players the small GoKart resource pack that turns trophy items into 3D cups.
 * By default the plugin serves the pack itself from a tiny web server on its own port;
 * you can instead host plugins/GoKart/GoKart-pack.zip anywhere and set resource-pack.url.
 */
final class ResourcePackHost implements Listener {

    /** The DominionTesting server on MineKeep (dominiontesting.minekeep.dev for web ports). */
    private static final String DEFAULT_MINEKEEP_SERVER = "dominiontesting";

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("gokart:trophies".getBytes());

    private final GoKartPlugin plugin;
    private File packFile;
    private String sha1;
    private HttpServer server;
    private String url;

    ResourcePackHost(GoKartPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", true)) return;
        try {
            packFile = new File(plugin.getDataFolder(), "GoKart-pack.zip");
            try (InputStream in = plugin.getResource("GoKart-pack.zip")) {
                if (in == null) {
                    plugin.getLogger().warning("Trophy resource pack is missing from the jar; trophies will show as ingots.");
                    return;
                }
                plugin.getDataFolder().mkdirs();
                Files.copy(in, packFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] bytes = Files.readAllBytes(packFile.toPath());
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));

            String external = cfg.getString("resource-pack.url", "");
            if (external != null && !external.isBlank()) {
                url = external;
            } else {
                int port = cfg.getInt("resource-pack.port", 2053);
                server = HttpServer.create(new InetSocketAddress(port), 0);
                server.createContext("/GoKart-pack.zip", exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
                server.start();
                String base = cfg.getString("resource-pack.public-base-url", "");
                String minekeep = cfg.getString("resource-pack.minekeep-server-name", "");
                String address0 = cfg.getString("resource-pack.public-address", "");
                if ((minekeep == null || minekeep.isBlank()) && (base == null || base.isBlank())
                        && (address0 == null || address0.isBlank())) {
                    minekeep = DEFAULT_MINEKEEP_SERVER; // works even with an older config.yml
                }
                if (minekeep != null && !minekeep.isBlank()) {
                    // MineKeep: https://<server>.minekeep.dev:<port> forwards to this port.
                    base = "https://" + minekeep.trim().toLowerCase() + ".minekeep.dev:" + port;
                } else if (base == null || base.isBlank()) {
                    String address = cfg.getString("resource-pack.public-address", "");
                    if (address == null || address.isBlank()) address = Bukkit.getIp();
                    if (address == null || address.isBlank()) {
                        // Minecraft refuses resource packs from localhost / private addresses, so don't send one.
                        plugin.getLogger().warning("Trophy resource pack is NOT being sent: no public address is set. "
                                + "On MineKeep set resource-pack.minekeep-server-name in plugins/GoKart/config.yml "
                                + "(e.g. \"example\" for example.minekeep.dev) and restart.");
                        stop();
                        return;
                    }
                    base = "http://" + address + ":" + port;
                }
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                String host = URI.create(base).getHost();
                if (host == null || host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                        || host.startsWith("10.") || host.equals("0.0.0.0")) {
                    plugin.getLogger().warning("Trophy resource pack is NOT being sent: " + base + " is a local address, "
                            + "which Minecraft blocks. Set resource-pack.minekeep-server-name (MineKeep) or "
                            + "resource-pack.public-base-url to your server's public address.");
                    stop();
                    return;
                }
                // ?v= makes sure players (and any proxy cache) pick up a new pack after updates.
                url = base + "/GoKart-pack.zip?v=" + sha1.substring(0, 10);
            }
            plugin.getLogger().info("Trophy resource pack ready at " + url);
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            for (Player p : Bukkit.getOnlinePlayers()) send(p);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            plugin.getLogger().warning("Couldn't start the trophy resource pack (" + e.getMessage()
                    + "). Trophies will show as ingots. Set resource-pack.port to a free port, or host "
                    + "GoKart-pack.zip yourself and set resource-pack.url.");
            stop();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        send(event.getPlayer());
    }

    void send(Player p) {
        if (url == null) return;
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), sha1);
            p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .required(plugin.getConfig().getBoolean("resource-pack.required", false))
                    .replace(false)
                    .prompt(Component.text("GoKart: adds 3D trophy cups for race winners.", NamedTextColor.GOLD)));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Bad resource-pack url: " + url);
        }
    }

    void stop() {
        if (server != null) server.stop(0);
        server = null;
    }
}
