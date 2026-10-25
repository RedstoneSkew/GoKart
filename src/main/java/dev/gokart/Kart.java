package dev.gokart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A detailed go-kart built out of block displays: chrome tube frame, bodywork, bucket seat,
 * engine with exhaust, chain + sprocket, brake disc, steering column, and wheels that actually
 * spin when you drive, front wheels that steer, and a steering wheel that turns with your input.
 *
 * Coordinates are kart-local blocks at size 1: +Z is forward, +X is the driver's left, the origin is
 * the seat (where the driver sits) and the ground is at y = -0.18. Everything scales by {@code k}.
 */
final class Kart {

    /** Ground clearance of the origin at size 1. */
    static final double RIDE_HEIGHT = 0.18;

    private static final float DEG = (float) (Math.PI / 180.0);

    private final World world;
    private final double k;
    private final Material paint;
    private final boolean racingTires;

    private BlockDisplay seat;                  // the vehicle the driver rides
    private final List<Entity> statics = new ArrayList<>();
    private final List<Animated> animated = new ArrayList<>();

    private float wheelSpin;     // radians
    private float steer;         // -1 .. 1, smoothed

    /** A part whose rotation changes every tick. */
    private record Animated(BlockDisplay display, Vector3f center, Vector3f size, Role role, float phase) {}

    private enum Role { FRONT_WHEEL_LEFT, FRONT_WHEEL_RIGHT, REAR_WHEEL, AXLE_PART, STEERING_WHEEL }

    Kart(Location at, double k, Material paint, boolean racingTires, int number, NamedTextColor numberColor) {
        this.world = at.getWorld();
        this.k = k;
        this.paint = paint;
        this.racingTires = racingTires;
        build(at, number, numberColor);
    }

    BlockDisplay seat() {
        return seat;
    }

    boolean isValid() {
        return seat != null && seat.isValid();
    }

    Location location() {
        return seat.getLocation();
    }

    // ================================================================ building

    private void build(Location at, int number, NamedTextColor numberColor) {
        final Material chrome = Material.IRON_BLOCK;
        final Material steel = Material.LIGHT_GRAY_CONCRETE;
        final Material black = Material.BLACK_CONCRETE;
        final Material dark = Material.POLISHED_DEEPSLATE;
        Quaternionf none = new Quaternionf();

        // Floor pan doubles as the vehicle the driver sits on.
        seat = spawn(at, Material.GRAY_CONCRETE, c(0, -0.115, 0.0), s(0.40, 0.015, 0.90), none);

        // --- Chrome tube frame
        fixed(at, chrome, c(0.17, -0.10, 0.0), s(0.025, 0.025, 1.0), none);
        fixed(at, chrome, c(-0.17, -0.10, 0.0), s(0.025, 0.025, 1.0), none);
        fixed(at, chrome, c(0, -0.10, 0.30), s(0.36, 0.025, 0.025), none);
        fixed(at, chrome, c(0, -0.10, -0.30), s(0.36, 0.025, 0.025), none);
        fixed(at, chrome, c(0, -0.07, -0.36), s(0.58, 0.03, 0.03), none);        // rear axle
        fixed(at, chrome, c(0, -0.09, 0.36), s(0.50, 0.022, 0.022), none);       // front beam
        fixed(at, steel, c(0.25, -0.09, 0.36), s(0.03, 0.07, 0.03), none);       // kingpins
        fixed(at, steel, c(-0.25, -0.09, 0.36), s(0.03, 0.07, 0.03), none);
        // Bearing carriers on the rear axle
        fixed(at, dark, c(0.16, -0.07, -0.36), s(0.04, 0.06, 0.06), none);
        fixed(at, dark, c(-0.12, -0.07, -0.36), s(0.04, 0.06, 0.06), none);

        // --- Bodywork
        fixed(at, paint, c(0, -0.085, 0.54), s(0.46, 0.07, 0.16), rotX(12));      // nose cone
        fixed(at, paint, c(0, -0.04, 0.43), s(0.34, 0.03, 0.15), rotX(25));       // front fairing
        fixed(at, Material.WHITE_CONCRETE, c(0, -0.081, 0.62), s(0.30, 0.012, 0.02), rotX(12)); // nose stripe
        fixed(at, chrome, c(0, -0.125, 0.645), s(0.42, 0.025, 0.025), none);      // front bumper bar
        for (int side : new int[]{1, -1}) {
            fixed(at, paint, c(0.305 * side, -0.09, 0.0), s(0.10, 0.07, 0.42), none);          // side pod
            fixed(at, black, c(0.305 * side, -0.128, 0.0), s(0.10, 0.012, 0.42), none);        // pod skid
            fixed(at, Material.WHITE_CONCRETE, c(0.356 * side, -0.085, 0.0), s(0.004, 0.018, 0.34), none); // stripe
            fixed(at, chrome, c(0.235 * side, -0.11, 0.12), s(0.11, 0.02, 0.02), none);       // pod mount bars
            fixed(at, chrome, c(0.235 * side, -0.11, -0.12), s(0.11, 0.02, 0.02), none);
        }
        fixed(at, black, c(0, -0.08, -0.545), s(0.72, 0.06, 0.07), none);          // rear bumper
        fixed(at, chrome, c(0.20, -0.10, -0.48), s(0.02, 0.02, 0.10), none);
        fixed(at, chrome, c(-0.20, -0.10, -0.48), s(0.02, 0.02, 0.10), none);

        // --- Bucket seat
        fixed(at, black, c(0, -0.07, -0.10), s(0.22, 0.025, 0.20), none);
        fixed(at, black, c(0, 0.05, -0.215), s(0.22, 0.24, 0.03), rotX(-15));
        fixed(at, black, c(0.115, -0.02, -0.12), s(0.025, 0.11, 0.20), none);
        fixed(at, black, c(-0.115, -0.02, -0.12), s(0.025, 0.11, 0.20), none);
        fixed(at, chrome, c(0, 0.02, -0.255), s(0.18, 0.015, 0.015), none);       // seat stay

        // --- Engine (driver's right, behind the seat) + exhaust
        fixed(at, dark, c(-0.22, -0.02, -0.30), s(0.12, 0.12, 0.13), none);       // crankcase
        fixed(at, chrome, c(-0.22, 0.06, -0.30), s(0.09, 0.04, 0.10), none);      // cylinder head
        for (int i = 0; i < 3; i++) {                                              // cooling fins
            fixed(at, steel, c(-0.22, 0.086 + i * 0.012, -0.30), s(0.10, 0.004, 0.11), none);
        }
        fixed(at, black, c(-0.22, 0.13, -0.30), s(0.02, 0.025, 0.02), none);      // spark plug cap
        fixed(at, paint, c(-0.22, 0.01, -0.205), s(0.09, 0.08, 0.06), none);      // air box
        fixed(at, chrome, c(-0.29, 0.0, -0.335), s(0.025, 0.025, 0.08), none);    // header
        fixed(at, chrome, c(-0.31, 0.01, -0.46), s(0.045, 0.045, 0.17), none);    // silencer
        fixed(at, black, c(-0.31, 0.01, -0.548), s(0.03, 0.03, 0.008), none);     // tailpipe
        fixed(at, black, c(-0.17, -0.045, -0.33), s(0.008, 0.035, 0.08), none);   // chain

        // --- Fuel tank, pedals, steering column
        fixed(at, Material.WHITE_CONCRETE, c(0, -0.07, 0.21), s(0.12, 0.06, 0.10), none);
        fixed(at, black, c(0, -0.035, 0.21), s(0.03, 0.012, 0.03), none);
        fixed(at, chrome, c(0.05, -0.075, 0.43), s(0.03, 0.05, 0.01), rotX(-20)); // pedals
        fixed(at, chrome, c(-0.05, -0.075, 0.43), s(0.03, 0.05, 0.01), rotX(-20));
        fixed(at, chrome, c(0, -0.015, 0.265), s(0.02, 0.02, 0.24), rotX(38));    // steering column

        // --- Number plate on the nose
        fixed(at, Material.WHITE_CONCRETE, c(0, -0.035, 0.47), s(0.14, 0.09, 0.008), rotX(-25));
        Location plate = at.clone();
        TextDisplay num = world.spawn(plate, TextDisplay.class, d -> {
            d.text(Component.text(String.valueOf(number), numberColor, TextDecoration.BOLD));
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setPersistent(false);
            d.setTeleportDuration(2);
            float sc = (float) (0.28 * k);
            Vector3f pos = c(0, -0.068, 0.478);
            d.setTransformation(new Transformation(pos, rotX(-25), new Vector3f(sc, sc, sc), new Quaternionf()));
        });
        statics.add(num);

        // --- Steering wheel (turns with your steering)
        animate(at, black, c(0, 0.07, 0.165), s(0.15, 0.15, 0.018), Role.STEERING_WHEEL, 0);
        animate(at, paint, c(0, 0.07, 0.165), s(0.05, 0.05, 0.024), Role.STEERING_WHEEL, 0);

        // --- Wheels: octagonal tires (two crossed squares) with rims and hubs, all spinning.
        Material tire = racingTires ? black : Material.COBBLESTONE;
        Material rim = racingTires ? chrome : Material.STONE;
        wheel(at, 0.33, -0.07, -0.36, 0.11, 0.12, tire, rim, Role.REAR_WHEEL);
        wheel(at, -0.33, -0.07, -0.36, 0.11, 0.12, tire, rim, Role.REAR_WHEEL);
        wheel(at, 0.31, -0.09, 0.36, 0.09, 0.09, tire, rim, Role.FRONT_WHEEL_LEFT);
        wheel(at, -0.31, -0.09, 0.36, 0.09, 0.09, tire, rim, Role.FRONT_WHEEL_RIGHT);

        // Sprocket and brake disc ride on the rear axle.
        animate(at, chrome, c(-0.17, -0.07, -0.36), s(0.008, 0.13, 0.13), Role.AXLE_PART, 0);
        animate(at, chrome, c(-0.17, -0.07, -0.36), s(0.008, 0.13, 0.13), Role.AXLE_PART, 45);
        animate(at, steel, c(0.115, -0.07, -0.36), s(0.01, 0.11, 0.11), Role.AXLE_PART, 0);
        animate(at, steel, c(0.115, -0.07, -0.36), s(0.01, 0.11, 0.11), Role.AXLE_PART, 45);
    }

    private void wheel(Location at, double x, double y, double z, double r, double w,
                       Material tire, Material rim, Role role) {
        double a = r * 1.75;                 // tire square size
        animate(at, tire, c(x, y, z), s(w, a, a), role, 0);
        animate(at, tire, c(x, y, z), s(w, a, a), role, 45);
        double rimSize = r * 1.05;
        double outer = x > 0 ? 0.004 : -0.004;
        animate(at, rim, c(x + outer, y, z), s(w + 0.004, rimSize, rimSize), role, 22.5f);
        animate(at, rim, c(x + outer, y, z), s(w + 0.004, rimSize, rimSize), role, 67.5f);
        animate(at, paint, c(x + outer * 2, y, z), s(w + 0.01, r * 0.35, r * 0.35), role, 0);   // hub cap
    }

    // ================================================================ animation

    /**
     * Move the whole kart.
     * @param at      origin (seat) position, with the kart's yaw and slope pitch
     * @param moved   blocks travelled this tick (negative when reversing) — spins the wheels
     * @param steerInput -1 (right) .. 1 (left)
     */
    void move(Location at, double moved, double steerInput, org.bukkit.entity.Entity driver) {
        if (!isValid()) return;
        // Wheels: angle travelled / radius, capped so fast spins don't strobe backwards.
        float spinStep = (float) (moved / (0.11 * k));
        spinStep = Math.max(-0.5f, Math.min(0.5f, spinStep));
        wheelSpin = (wheelSpin + spinStep) % (float) (Math.PI * 2);
        steer += (float) ((steerInput - steer) * 0.35);

        seat.teleport(at, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        for (Entity e : statics) e.teleport(at);
        for (Animated part : animated) {
            part.display().teleport(at);
            part.display().setInterpolationDelay(0);
            part.display().setInterpolationDuration(2);
            part.display().setTransformation(transform(part.center(), part.size(), rotationFor(part)));
        }
        if (driver != null && driver.isValid()) driver.setRotation(at.getYaw(), 0f);
    }

    private Quaternionf rotationFor(Animated part) {
        float phase = part.phase() * DEG;
        return switch (part.role()) {
            case REAR_WHEEL, AXLE_PART -> new Quaternionf().rotateX(wheelSpin + phase);
            case FRONT_WHEEL_LEFT, FRONT_WHEEL_RIGHT ->
                    new Quaternionf().rotateY(steer * 25f * DEG).rotateX(wheelSpin * 1.22f + phase);
            case STEERING_WHEEL -> rotX(38).rotateZ(-steer * 90f * DEG);
        };
    }

    void remove() {
        if (seat != null && seat.isValid()) seat.remove();
        seat = null;
        for (Entity e : statics) if (e.isValid()) e.remove();
        statics.clear();
        for (Animated a : animated) if (a.display().isValid()) a.display().remove();
        animated.clear();
    }

    // ================================================================ helpers

    private Vector3f c(double x, double y, double z) {
        return new Vector3f((float) (x * k), (float) (y * k), (float) (z * k));
    }

    private Vector3f s(double x, double y, double z) {
        return new Vector3f((float) (x * k), (float) (y * k), (float) (z * k));
    }

    private static Quaternionf rotX(float degrees) {
        return new Quaternionf().rotateX(degrees * DEG);
    }

    /** Box of {@code size} centred on {@code center}, rotated about its own centre. */
    private static Transformation transform(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f half = new Vector3f(size).mul(0.5f);
        Vector3f translation = new Vector3f(center).sub(rotation.transform(new Vector3f(half)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private BlockDisplay spawn(Location at, Material material, Vector3f center, Vector3f size, Quaternionf rot) {
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(material.createBlockData());
            d.setTransformation(transform(center, size, rot));
            d.setTeleportDuration(2);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.FIXED);
        });
    }

    private void fixed(Location at, Material material, Vector3f center, Vector3f size, Quaternionf rot) {
        statics.add(spawn(at, material, center, size, rot));
    }

    private void animate(Location at, Material material, Vector3f center, Vector3f size, Role role, float phase) {
        Animated a = new Animated(null, center, size, role, phase);
        BlockDisplay d = spawn(at, material, center, size, rotationFor(a));
        animated.add(new Animated(d, center, size, role, phase));
    }
}
