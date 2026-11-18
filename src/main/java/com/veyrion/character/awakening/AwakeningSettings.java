package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * Validated, immutable view of {@code awakening.yml}. Every key missing from the server owner's file falls back
 * to the bundled file; invalid values fall back to the bundled value with a warning. Rituals keep the instance
 * they started with, so a reload never changes a running ritual.
 */
public record AwakeningSettings(
        boolean enabled,
        boolean allowCommandRoll,
        Altar altar,
        Timing timing,
        Movement movement,
        Effects effects,
        BossBarSpec bossBar,
        Gui gui,
        Stage activation,
        Stage resonance,
        Stage accumulation,
        Stage collapse,
        List<String> resonanceRaces,
        Map<String, RaceVisual> races,
        RaceVisual fallbackVisual,
        Map<String, String> messages) {

    public enum RevealStyle { PILLAR, HELIX, SHOCKWAVE, VORTEX }

    public record Altar(long cooldownMs, double maxStartDistance, double centerOffsetY, double leashRadius) {}

    /** All lengths are in ticks. */
    public record Timing(int tickInterval, int activation, int resonancePerRace, int accumulation, int collapse,
                         int reveal, int completion) {}

    public record Movement(boolean lock, boolean lockSpeed, boolean protectFromDamage) {}

    public record Effects(double radius, double height, double multiplier, int maxParticlesPerStep) {}

    public record BossBarSpec(boolean enabled, String title, BossBar.Color color, BossBar.Overlay overlay) {}

    public record Button(Material material, String name, List<String> lore) {}

    public record Gui(String title, Button begin, Button cancel, Button info) {}

    /** One generic stage. Unused parts are NONE / empty. */
    public record Stage(TitleSpec title, ParticleSpec particle, ParticleSpec accent, SoundSpec sound,
                        SoundSpec secondSound, int soundEveryTicks, int burstCount) {}

    public record Reveal(RevealStyle style, ParticleSpec primary, ParticleSpec secondary, SoundSpec sound,
                         SoundSpec accentSound, TitleSpec title, String lore) {}

    public record RaceVisual(@Nullable TextColor color, int sides, boolean star, ParticleSpec particle,
                             ParticleSpec accent, SoundSpec sound, TitleSpec title, Reveal reveal) {}

    public RaceVisual visualFor(String raceId) {
        RaceVisual visual = races.get(raceId.toLowerCase(Locale.ROOT));
        return visual != null ? visual : fallbackVisual;
    }

    /** Raw MiniMessage text of a message key (see the {@code messages} section). */
    public String message(String key) {
        String value = messages.get(key);
        return value != null ? value : "<red>Missing awakening message: " + key;
    }

    /** Ticks from ritual start until the reveal ends (the whole animation), for the given number of races. */
    public int totalTicks(int raceCount) {
        return timing.activation() + timing.resonancePerRace() * raceCount + timing.accumulation()
                + timing.collapse() + timing.reveal() + timing.completion();
    }

    // ------------------------------------------------------------------------------------------------ parsing

    /**
     * @param user    the server owner's file
     * @param bundled the file bundled in the jar (also attached to {@code user} as its defaults)
     */
    public static AwakeningSettings parse(YamlConfiguration user, YamlConfiguration bundled, Logger logger) {
        // A race that only exists in the owner's file inherits every missing key from 'default-race'.
        // ('bundled' must be a private, freshly loaded copy: it is modified here.)
        ConfigurationSection template = bundled.getConfigurationSection("default-race");
        ConfigurationSection ownRaces = user.getConfigurationSection("races");
        if (template != null && ownRaces != null) {
            for (String key : ownRaces.getKeys(false)) {
                if (bundled.isConfigurationSection("races." + key)) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : template.getValues(true).entrySet()) {
                    if (!(entry.getValue() instanceof ConfigurationSection)) {
                        bundled.set("races." + key + "." + entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        user.setDefaults(bundled);
        Reader r = new Reader(user, bundled, logger);

        Altar altar = new Altar(
                r.longValue("altar.interaction-cooldown-ms", 0, 60_000),
                r.number("altar.max-start-distance", 1, 64),
                r.number("altar.center-offset-y", -2, 10),
                r.number("altar.leash-radius", 1.5, 64));

        Timing timing = new Timing(
                r.integer("ritual.tick-interval", 1, 10),
                r.integer("ritual.activation-ticks", 0, 6000),
                r.integer("ritual.resonance-ticks-per-race", 0, 6000),
                r.integer("ritual.accumulation-ticks", 0, 6000),
                r.integer("ritual.collapse-ticks", 0, 6000),
                r.integer("ritual.reveal-ticks", 20, 6000),
                r.integer("ritual.completion-ticks", 0, 6000));

        Movement movement = new Movement(
                user.getBoolean("movement.lock", true),
                user.getBoolean("movement.lock-speed", true),
                user.getBoolean("movement.protect-from-damage", true));

        Effects effects = new Effects(
                r.number("effects.radius", 0.5, 16),
                r.number("effects.height", 0.5, 16),
                r.number("effects.particle-multiplier", 0.0, 10),
                r.integer("effects.max-particles-per-step", 0, 2000));

        BossBarSpec bossBar = new BossBarSpec(
                user.getBoolean("boss-bar.enabled", true),
                r.text("boss-bar.title"),
                r.enumValue("boss-bar.color", BossBar.Color.class),
                r.enumValue("boss-bar.overlay", BossBar.Overlay.class));

        Gui gui = new Gui(r.text("gui.title"),
                r.button("gui.begin"), r.button("gui.cancel"), r.button("gui.info"));

        Stage activation = new Stage(
                r.title("stages.activation.title"),
                r.particle("stages.activation.particle"),
                ParticleSpec.NONE,
                r.sound("stages.activation.sound"),
                r.sound("stages.activation.ambient-sound"),
                r.integer("stages.activation.ambient-every-ticks", 1, 6000),
                0);
        Stage resonance = new Stage(
                r.title("stages.resonance.title"),
                ParticleSpec.NONE, ParticleSpec.NONE, SoundSpec.NONE, SoundSpec.NONE, 1, 0);
        Stage accumulation = new Stage(
                r.title("stages.accumulation.title"),
                r.particle("stages.accumulation.particle"),
                r.particle("stages.accumulation.accent"),
                r.sound("stages.accumulation.sound"),
                SoundSpec.NONE,
                r.integer("stages.accumulation.sound-every-ticks", 1, 6000),
                0);
        Stage collapse = new Stage(
                r.title("stages.collapse.title"),
                r.particle("stages.collapse.particle"),
                r.particle("stages.collapse.burst-particle"),
                r.sound("stages.collapse.sound"),
                r.sound("stages.collapse.burst-sound"),
                1,
                r.integer("stages.collapse.burst-count", 0, 1000));

        List<String> resonanceRaces = new ArrayList<>();
        for (String id : user.getStringList("stages.resonance.races")) {
            String clean = id.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty() && !resonanceRaces.contains(clean)) {
                resonanceRaces.add(clean);
            }
        }
        if (resonanceRaces.isEmpty()) {
            logger.warning("awakening.yml: 'stages.resonance.races' is empty; using the bundled list.");
            for (String id : bundled.getStringList("stages.resonance.races")) {
                resonanceRaces.add(id.toLowerCase(Locale.ROOT));
            }
        }

        Set<String> raceIds = new LinkedHashSet<>();
        addKeys(raceIds, user.getConfigurationSection("races"));
        addKeys(raceIds, bundled.getConfigurationSection("races"));
        Map<String, RaceVisual> races = new LinkedHashMap<>();
        for (String id : raceIds) {
            races.put(id.toLowerCase(Locale.ROOT), r.raceVisual("races." + id));
        }
        RaceVisual fallback = r.raceVisual("default-race");

        Map<String, String> messages = new LinkedHashMap<>();
        collectMessages(messages, bundled.getConfigurationSection("messages"), "");
        collectMessages(messages, user.getConfigurationSection("messages"), "");

        return new AwakeningSettings(user.getBoolean("enabled", true), user.getBoolean("allow-command-roll", true),
                altar, timing, movement, effects, bossBar, gui, activation, resonance, accumulation, collapse,
                List.copyOf(resonanceRaces), races, fallback, Map.copyOf(messages));
    }

    private static void addKeys(Set<String> target, @Nullable ConfigurationSection section) {
        if (section != null) {
            target.addAll(section.getKeys(false));
        }
    }

    private static void collectMessages(Map<String, String> target, @Nullable ConfigurationSection section,
                                        String prefix) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                collectMessages(target, section.getConfigurationSection(key), prefix + key + ".");
            } else {
                String value = section.getString(key);
                if (value != null) {
                    target.put(prefix + key, value);
                }
            }
        }
    }

    /** Typed reads with range checks; bad values fall back to the bundled value. */
    private static final class Reader {
        private final YamlConfiguration user;
        private final YamlConfiguration bundled;
        private final Logger logger;

        Reader(YamlConfiguration user, YamlConfiguration bundled, Logger logger) {
            this.user = user;
            this.bundled = bundled;
            this.logger = logger;
        }

        private void warn(String path, String problem) {
            logger.warning("awakening.yml: '" + path + "' " + problem + "; using the default.");
        }

        int integer(String path, int min, int max) {
            int value = user.getInt(path, bundled.getInt(path));
            if (value < min || value > max) {
                warn(path, "must be between " + min + " and " + max);
                return Math.max(min, Math.min(max, bundled.getInt(path)));
            }
            return value;
        }

        long longValue(String path, long min, long max) {
            long value = user.getLong(path, bundled.getLong(path));
            if (value < min || value > max) {
                warn(path, "must be between " + min + " and " + max);
                return Math.max(min, Math.min(max, bundled.getLong(path)));
            }
            return value;
        }

        double number(String path, double min, double max) {
            double value = user.getDouble(path, bundled.getDouble(path));
            if (!Double.isFinite(value) || value < min || value > max) {
                warn(path, "must be between " + min + " and " + max);
                return Math.max(min, Math.min(max, bundled.getDouble(path)));
            }
            return value;
        }

        String text(String path) {
            String value = user.getString(path);
            return value != null ? value : bundled.getString(path, "");
        }

        <E extends Enum<E>> E enumValue(String path, Class<E> type) {
            String raw = user.getString(path, "");
            try {
                return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                if (!raw.isBlank()) {
                    warn(path, "is not a valid " + type.getSimpleName());
                }
                try {
                    return Enum.valueOf(type, bundled.getString(path, "").trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException again) {
                    return type.getEnumConstants()[0];
                }
            }
        }

        Button button(String path) {
            Material material = Material.matchMaterial(user.getString(path + ".material", ""));
            if (material == null || material.isAir() || !material.isItem()) {
                warn(path + ".material", "is not a valid item");
                material = Material.matchMaterial(bundled.getString(path + ".material", "STONE"));
                if (material == null) {
                    material = Material.STONE;
                }
            }
            List<String> lore = user.contains(path + ".lore")
                    ? user.getStringList(path + ".lore") : bundled.getStringList(path + ".lore");
            return new Button(material, text(path + ".name"), List.copyOf(lore));
        }

        TitleSpec title(String path) {
            return new TitleSpec(text(path + ".title"), text(path + ".subtitle"),
                    integer(path + ".fade-in", 0, 600), integer(path + ".stay", 0, 6000),
                    integer(path + ".fade-out", 0, 600));
        }

        SoundSpec sound(String path) {
            String key = user.getString(path + ".key", "");
            if (!key.isBlank() && NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT)) == null) {
                warn(path + ".key", "is not a valid sound key");
                return SoundSpec.NONE;
            }
            float volume = (float) number(path + ".volume", 0, 10);
            float pitch = (float) number(path + ".pitch", 0.5, 2.0);
            return new SoundSpec(key.trim().toLowerCase(Locale.ROOT), volume, pitch);
        }

        ParticleSpec particle(String path) {
            String raw = user.getString(path + ".type", "");
            Particle type;
            try {
                type = Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                if (!ParticleSpec.supported(type)) {
                    warn(path + ".type", "needs extra data and is not supported (use a plain particle or DUST)");
                    type = Particle.END_ROD;
                }
            } catch (IllegalArgumentException e) {
                warn(path + ".type", "is not a valid particle");
                type = Particle.END_ROD;
            }
            TextColor color = null;
            String colorRaw = user.getString(path + ".color", "");
            if (!colorRaw.isBlank()) {
                color = parseColor(colorRaw);
                if (color == null) {
                    warn(path + ".color", "is not a valid color");
                }
            }
            return new ParticleSpec(type, integer(path + ".count", 0, 100),
                    number(path + ".spread", 0, 10), number(path + ".speed", 0, 10), color,
                    (float) number(path + ".size", 0.1, 5));
        }

        RaceVisual raceVisual(String base) {
            TextColor color = null;
            String colorRaw = user.getString(base + ".color", "");
            if (!colorRaw.isBlank()) {
                color = parseColor(colorRaw);
                if (color == null) {
                    warn(base + ".color", "is not a valid color");
                }
            }
            String shape = base + ".resonance.shape";
            Reveal reveal = new Reveal(
                    enumValue(base + ".reveal.style", RevealStyle.class),
                    particle(base + ".reveal.particle"),
                    particle(base + ".reveal.secondary"),
                    sound(base + ".reveal.sound"),
                    sound(base + ".reveal.accent-sound"),
                    title(base + ".reveal.title"),
                    text(base + ".reveal.lore"));
            return new RaceVisual(color, integer(shape + ".sides", 3, 12), user.getBoolean(shape + ".star", false),
                    particle(base + ".resonance.particle"), particle(base + ".resonance.accent"),
                    sound(base + ".resonance.sound"), title(base + ".resonance.title"), reveal);
        }

        private static @Nullable TextColor parseColor(String raw) {
            String value = raw.trim();
            if (value.startsWith("#")) {
                return TextColor.fromHexString(value);
            }
            return NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        }
    }
}
