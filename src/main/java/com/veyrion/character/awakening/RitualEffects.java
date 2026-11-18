package com.veyrion.character.awakening;

import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Particle drawing helpers. Everything is sent to ONE player only ({@code Player#spawnParticle}), so bystanders
 * never see or hear a ritual. A per-step budget caps the number of particles to protect server and client.
 * One instance belongs to one session; not thread-safe, main thread only.
 */
final class RitualEffects {

    private static final double TAU = Math.PI * 2.0;

    private final AwakeningSettings.Effects config;
    private int budget;

    RitualEffects(AwakeningSettings.Effects config) {
        this.config = config;
    }

    void beginStep() {
        budget = config.maxParticlesPerStep();
    }

    /** Scales a base amount by intensity (0-1+) and the configured multiplier; 0 only if the multiplier is 0. */
    int scale(int base, double intensity) {
        if (config.multiplier() <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(base * Math.max(0.05, intensity) * config.multiplier()));
    }

    // ---------------------------------------------------------------------------------------------- primitives

    void point(Player player, ParticleSpec spec, TextColor raceColor, double x, double y, double z) {
        if (spec.disabled() || budget <= 0) {
            return;
        }
        budget -= spec.count();
        if (spec.isDust()) {
            TextColor tint = spec.color() != null ? spec.color() : raceColor;
            player.spawnParticle(Particle.DUST, x, y, z, spec.count(), spec.spread(), spec.spread(), spec.spread(),
                    0.0, new Particle.DustOptions(Color.fromRGB(tint.value()), spec.size()));
        } else {
            player.spawnParticle(spec.type(), x, y, z, spec.count(), spec.spread(), spec.spread(), spec.spread(),
                    spec.speed());
        }
    }

    /** A particle that flies away from the origin (count 0 makes the offset a direction for most particles). */
    private void directional(Player player, ParticleSpec spec, TextColor raceColor, double x, double y, double z,
                             double dx, double dy, double dz) {
        if (spec.disabled() || budget <= 0) {
            return;
        }
        if (spec.isDust()) {
            point(player, spec, raceColor, x + dx, y + dy, z + dz);
            return;
        }
        budget -= 1;
        player.spawnParticle(spec.type(), x, y, z, 0, dx, dy, dz, spec.speed());
    }

    // ------------------------------------------------------------------------------------------------ shapes

    void ring(Player player, Location c, double dy, double radius, int points, double rotation,
              ParticleSpec spec, TextColor color) {
        for (int i = 0; i < points; i++) {
            double angle = rotation + TAU * i / points;
            point(player, spec, color, c.getX() + Math.cos(angle) * radius, c.getY() + dy,
                    c.getZ() + Math.sin(angle) * radius);
        }
    }

    /** Regular polygon; with {@code star} and 5+ sides every second vertex is joined (pentagram, hexagram). */
    void polygon(Player player, Location c, double dy, double radius, int sides, boolean star, double rotation,
                 int pointsPerEdge, ParticleSpec spec, TextColor color) {
        int skip = star && sides >= 5 ? 2 : 1;
        for (int k = 0; k < sides; k++) {
            double a1 = rotation + TAU * k / sides;
            double a2 = rotation + TAU * (k + skip) / sides;
            double x1 = Math.cos(a1) * radius;
            double z1 = Math.sin(a1) * radius;
            double x2 = Math.cos(a2) * radius;
            double z2 = Math.sin(a2) * radius;
            for (int j = 0; j < pointsPerEdge; j++) {
                double t = (double) j / pointsPerEdge;
                point(player, spec, color, c.getX() + x1 + (x2 - x1) * t, c.getY() + dy,
                        c.getZ() + z1 + (z2 - z1) * t);
            }
        }
    }

    /** Random floating particles inside a cylinder standing on {@code c}. */
    void motes(Player player, Location c, double radius, double height, int count, ParticleSpec spec,
               TextColor color) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(TAU);
            double r = radius * Math.sqrt(random.nextDouble());
            point(player, spec, color, c.getX() + Math.cos(angle) * r, c.getY() + random.nextDouble(height),
                    c.getZ() + Math.sin(angle) * r);
        }
    }

    /**
     * Spiral arms from {@code startY} to {@code startY + height}. The radius goes from {@code r0} (bottom) to
     * {@code r1} (top); {@code rotation} spins the whole figure.
     */
    void spiral(Player player, Location c, double startY, double height, double r0, double r1, int arms,
                int pointsPerArm, double turns, double rotation, ParticleSpec spec, TextColor color) {
        for (int arm = 0; arm < arms; arm++) {
            double offset = TAU * arm / arms;
            for (int i = 0; i < pointsPerArm; i++) {
                double t = (double) i / pointsPerArm;
                double angle = rotation + offset + t * turns * TAU;
                double r = r0 + (r1 - r0) * t;
                point(player, spec, color, c.getX() + Math.cos(angle) * r, c.getY() + startY + t * height,
                        c.getZ() + Math.sin(angle) * r);
            }
        }
    }

    /** Particles flying outward from {@code c} in all directions. */
    void burst(Player player, Location c, int count, ParticleSpec spec, TextColor color) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double y = random.nextDouble(-1, 1);
            double angle = random.nextDouble(TAU);
            double flat = Math.sqrt(1 - y * y);
            directional(player, spec, color, c.getX(), c.getY(), c.getZ(),
                    Math.cos(angle) * flat * 1.5, y * 1.5, Math.sin(angle) * flat * 1.5);
        }
    }

    // --------------------------------------------------------------------------------------------- reveal

    /**
     * One animation step of a race-specific reveal. {@code progress} is 0-1 over the reveal, {@code spin} an
     * ever-increasing angle. Each style looks clearly different.
     */
    void reveal(Player player, Location ground, AwakeningSettings.Reveal reveal, TextColor color,
                double progress, double spin) {
        double radius = config.radius();
        double height = config.height();
        switch (reveal.style()) {
            case PILLAR -> {
                double h = height * Math.min(1.0, progress * 3.0);
                spiral(player, ground, 0, h, 0.9, 0.9, 2, scale(14, 1), 2.0, spin, reveal.primary(), color);
                ring(player, ground, 0.05, radius * (0.4 + 0.6 * progress), scale(18, 1), spin * 0.5,
                        reveal.primary(), color);
                point(player, reveal.secondary(), color, ground.getX(), ground.getY() + h, ground.getZ());
                motes(player, ground, 0.6, h, scale(2, 1), reveal.secondary(), color);
            }
            case HELIX -> {
                spiral(player, ground, 0, height, 1.2, 1.2, 2, scale(16, 1), 2.5, spin, reveal.primary(), color);
                spiral(player, ground, 0, height, 0.6, 0.6, 2, scale(8, 1), 2.5, -spin, reveal.secondary(), color);
                motes(player, ground, radius, height, scale(3, 1), reveal.secondary(), color);
            }
            case SHOCKWAVE -> {
                double wave = (progress * 3.0) % 1.0;
                ring(player, ground, 0.1, radius * 0.3 + radius * 1.1 * wave, scale(30, 1), 0, reveal.primary(), color);
                ring(player, ground, 0.1, radius * 0.2 + radius * 0.9 * ((wave + 0.5) % 1.0), scale(20, 1), 0,
                        reveal.secondary(), color);
                motes(player, ground, radius * 0.5, height * 0.5, scale(2, 1), reveal.secondary(), color);
            }
            case VORTEX -> {
                spiral(player, ground, 0, height * 1.3, radius, radius * 0.3, 3, scale(14, 1), 1.5, spin,
                        reveal.primary(), color);
                spiral(player, ground, 0, height * 1.3, radius * 0.7, radius * 0.1, 2, scale(8, 1), 2.0, -spin,
                        reveal.secondary(), color);
                ring(player, ground, 0.05, radius, scale(16, 1), -spin, reveal.secondary(), color);
            }
        }
    }
}
