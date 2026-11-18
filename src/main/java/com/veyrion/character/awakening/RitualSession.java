package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.race.RaceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * One player's ritual: a single repeating task that plays the cinematic as a pure function of elapsed ticks.
 *
 * <p>The race is already stored in the database before a session exists; the session only displays it. Nothing
 * in here reads or changes the race result, and the soul-resonance sequence is a fixed visual list.
 */
final class RitualSession implements Runnable {

    enum Phase { ACTIVATION, RESONANCE, ACCUMULATION, COLLAPSE, REVEAL, COMPLETION }

    enum EndReason { COMPLETED, QUIT, DEATH, LEASH, WORLD, DISABLE, ERROR }

    /** One entry of the visual-only resonance sequence. */
    private record ResonanceStep(AwakeningSettings.RaceVisual visual, Component name, TextColor color,
                                 TagResolver[] resolvers) {}

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final TextColor NEUTRAL = TextColor.color(0xB388FF);

    private final VeyrionCharacterPlugin plugin;
    private final AwakeningService service;
    private final UUID playerId;
    private final String playerName;
    private final AwakeningSettings cfg;
    private final UUID worldId;
    private final Location ground;
    private final Location center;
    private final double leashSquared;
    private final String raceId;
    private final double chance;
    private final AwakeningSettings.RaceVisual raceVisual;
    private final TextColor raceColor;
    private final TagResolver[] raceResolvers;
    private final List<ResonanceStep> resonance;
    private final RitualEffects fx;
    private final BossBar bar;

    private final int interval;
    private final int activationEnd;
    private final int resonanceEnd;
    private final int accumulationEnd;
    private final int collapseEnd;
    private final int revealEnd;
    private final int total;

    private int elapsed;
    private double spin;
    private @Nullable Phase lastPhase;
    private int lastResonanceIndex = -1;
    private @Nullable BukkitTask task;
    private boolean ended;
    private boolean barShown;
    private @Nullable Float originalWalkSpeed;
    private @Nullable Float originalFlySpeed;

    RitualSession(VeyrionCharacterPlugin plugin, AwakeningService service, Player player, AwakeningSettings cfg,
                  Location ground, Location center, RaceDefinition race, double chance) {
        this.plugin = plugin;
        this.service = service;
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.cfg = cfg;
        this.worldId = ground.getWorld().getUID();
        this.ground = ground;
        this.center = center;
        this.leashSquared = cfg.altar().leashRadius() * cfg.altar().leashRadius();
        this.raceId = race.id();
        this.chance = chance;
        this.raceVisual = cfg.visualFor(race.id());
        this.raceColor = raceVisual.color() != null ? raceVisual.color() : race.color();
        this.raceResolvers = resolversFor(race.displayName(), race.description(), raceColor, playerName, chance);
        this.fx = new RitualEffects(cfg.effects());

        // The sequence only shows races that exist in races.yml; it never depends on the result.
        List<ResonanceStep> steps = new ArrayList<>();
        for (String id : cfg.resonanceRaces()) {
            RaceDefinition definition = plugin.races().settings().find(id);
            if (definition == null) {
                continue;
            }
            AwakeningSettings.RaceVisual visual = cfg.visualFor(id);
            TextColor color = visual.color() != null ? visual.color() : definition.color();
            steps.add(new ResonanceStep(visual, Component.text(definition.displayName(), color), color,
                    resolversFor(definition.displayName(), definition.description(), color, playerName, 0)));
        }
        this.resonance = List.copyOf(steps);

        this.interval = cfg.timing().tickInterval();
        this.activationEnd = cfg.timing().activation();
        this.resonanceEnd = activationEnd + cfg.timing().resonancePerRace() * resonance.size();
        this.accumulationEnd = resonanceEnd + cfg.timing().accumulation();
        this.collapseEnd = accumulationEnd + cfg.timing().collapse();
        this.revealEnd = collapseEnd + cfg.timing().reveal();
        this.total = revealEnd + cfg.timing().completion();

        this.bar = BossBar.bossBar(MINI.deserialize(cfg.bossBar().title()), 0f, cfg.bossBar().color(),
                cfg.bossBar().overlay());
    }

    static TagResolver[] resolversFor(String name, String description, TextColor shownColor, String player,
                                      double chance) {
        return new TagResolver[] {
                Placeholder.component("race", Component.text(name, shownColor)),
                Placeholder.unparsed("race_name", name),
                Placeholder.unparsed("description", description),
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("chance", String.format(Locale.ROOT, "%.1f", chance * 100))
        };
    }

    UUID playerId() {
        return playerId;
    }

    UUID worldId() {
        return worldId;
    }

    String raceId() {
        return raceId;
    }

    double chance() {
        return chance;
    }

    TagResolver[] raceResolvers() {
        return raceResolvers;
    }

    /** The original speeds, for crash recovery; null until the lock changed them. */
    @Nullable Float originalWalkSpeed() {
        return originalWalkSpeed;
    }

    @Nullable Float originalFlySpeed() {
        return originalFlySpeed;
    }

    /** The reveal lore line, ready to send (used for the interrupted fallback too). */
    @Nullable Component lore() {
        String lore = raceVisual.reveal().lore();
        return lore.isBlank() ? null : MINI.deserialize(lore, raceResolvers);
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    /** Locks movement, shows the boss bar and starts the single repeating task. Main thread. */
    void start(Player player) {
        if (cfg.movement().lock() && cfg.movement().lockSpeed()) {
            originalWalkSpeed = player.getWalkSpeed();
            originalFlySpeed = player.getFlySpeed();
            player.setWalkSpeed(0f);
            player.setFlySpeed(0f);
        }
        if (cfg.bossBar().enabled()) {
            player.showBossBar(bar);
            barShown = true;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 0L, interval);
    }

    /**
     * Stops the task and removes every temporary effect. Idempotent. {@code player} may be null if the player
     * is gone; speeds are then restored by the pending store on the next join.
     */
    void close(@Nullable Player player, boolean clearTitle) {
        ended = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (player != null) {
            if (barShown) {
                player.hideBossBar(bar);
            }
            if (originalWalkSpeed != null) {
                player.setWalkSpeed(originalWalkSpeed);
            }
            if (originalFlySpeed != null) {
                player.setFlySpeed(originalFlySpeed);
            }
            if (clearTitle) {
                player.clearTitle();
            }
        }
        barShown = false;
        originalWalkSpeed = null;
        originalFlySpeed = null;
    }

    boolean locksMovement() {
        return cfg.movement().lock();
    }

    boolean protectsFromDamage() {
        return cfg.movement().protectFromDamage();
    }

    boolean ended() {
        return ended;
    }

    // ------------------------------------------------------------------------------------------------ the task

    @Override
    public void run() {
        if (ended) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            service.end(this, EndReason.QUIT);
            return;
        }
        if (player.isDead()) {
            service.end(this, EndReason.DEATH);
            return;
        }
        if (Bukkit.getWorld(worldId) == null) {
            service.end(this, EndReason.WORLD);
            return;
        }
        if (!player.getWorld().getUID().equals(worldId)
                || player.getLocation().distanceSquared(center) > leashSquared) {
            service.end(this, EndReason.LEASH);
            return;
        }
        try {
            step(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Awakening ritual of " + playerName + " failed", e);
            service.end(this, EndReason.ERROR);
            return;
        }
        elapsed += interval;
        if (elapsed >= total) {
            service.end(this, EndReason.COMPLETED);
        }
    }

    private Phase phaseAt(int t) {
        if (t < activationEnd) {
            return Phase.ACTIVATION;
        }
        if (t < resonanceEnd) {
            return Phase.RESONANCE;
        }
        if (t < accumulationEnd) {
            return Phase.ACCUMULATION;
        }
        if (t < collapseEnd) {
            return Phase.COLLAPSE;
        }
        if (t < revealEnd) {
            return Phase.REVEAL;
        }
        return Phase.COMPLETION;
    }

    /** True once per {@code every} ticks (always at the first step of a phase boundary multiple). */
    private boolean due(int t, int every) {
        return t % every < interval;
    }

    private void step(Player player) {
        fx.beginStep();
        int t = elapsed;
        Phase phase = phaseAt(t);

        // Animation intensity rises through resonance and accumulation; rotation speeds up, then slows down
        // over the last part so the collapse and reveal arrive calmly.
        double q = accumulationEnd == 0 ? 1.0 : Math.min(1.0, (double) t / accumulationEnd);
        double intensity = 0.3 + 0.7 * q;
        double slow = q > 0.85 ? Math.max(0.15, (1.0 - q) / 0.15) : 1.0;
        if (phase.ordinal() <= Phase.ACCUMULATION.ordinal()) {
            spin += (0.05 + 0.2 * Math.min(1.0, q / 0.85)) * slow * interval;
        } else {
            spin += 0.05 * interval;
        }

        if (phase != lastPhase) {
            lastPhase = phase;
            enter(player, phase);
        }
        if (cfg.bossBar().enabled()) {
            bar.progress((float) Math.max(0.0, Math.min(1.0, (double) t / total)));
        }

        switch (phase) {
            case ACTIVATION -> activation(player, t);
            case RESONANCE -> resonance(player, t, intensity);
            case ACCUMULATION -> accumulation(player, t, intensity);
            case COLLAPSE -> collapse(player, t);
            case REVEAL -> fx.reveal(player, ground, raceVisual.reveal(), raceColor,
                    (double) (t - collapseEnd) / Math.max(1, revealEnd - collapseEnd), spin * 3);
            case COMPLETION -> fx.motes(player, ground, cfg.effects().radius() * 0.6, cfg.effects().height() * 0.5,
                    fx.scale(1, 1), raceVisual.reveal().primary(), raceColor);
        }
    }

    private void enter(Player player, Phase phase) {
        switch (phase) {
            case ACTIVATION -> {
                cfg.activation().title().show(player);
                cfg.activation().sound().play(player, center);
            }
            case RESONANCE -> cfg.resonance().title().show(player);
            case ACCUMULATION -> cfg.accumulation().title().show(player);
            case COLLAPSE -> {
                cfg.collapse().title().show(player);
                cfg.collapse().sound().play(player, center);
            }
            case REVEAL -> {
                // Dimensional collapse ends in a controlled burst, then the real result is revealed.
                AwakeningSettings.Stage collapse = cfg.collapse();
                fx.burst(player, center, fx.scale(collapse.burstCount(), 1), collapse.accent(), raceColor);
                collapse.secondSound().play(player, center);
                AwakeningSettings.Reveal reveal = raceVisual.reveal();
                reveal.sound().play(player, center);
                reveal.accentSound().play(player, center);
                reveal.title().show(player, raceResolvers);
                Component lore = lore();
                if (lore != null) {
                    player.sendMessage(lore);
                }
            }
            case COMPLETION -> { }
        }
    }

    private void activation(Player player, int t) {
        AwakeningSettings.Stage stage = cfg.activation();
        double p = activationEnd == 0 ? 1.0 : (double) t / activationEnd;
        double radius = cfg.effects().radius();
        fx.ring(player, ground, 0.05, radius * (0.3 + 0.5 * p), fx.scale(14, 1), spin, stage.particle(), NEUTRAL);
        fx.motes(player, ground, radius * 0.6, cfg.effects().height() * 0.6, fx.scale(2, 1), stage.particle(),
                NEUTRAL);
        if (due(t, stage.soundEveryTicks())) {
            stage.secondSound().play(player, center);
        }
    }

    private void resonance(Player player, int t, double intensity) {
        int perRace = cfg.timing().resonancePerRace();
        int index = Math.min(resonance.size() - 1, (t - activationEnd) / perRace);
        ResonanceStep current = resonance.get(index);
        if (index != lastResonanceIndex) {
            lastResonanceIndex = index;
            current.visual().sound().play(player, center);
            if (index > 0) {
                // The first race shares the moment with the "Soul Resonance" title shown on entering the phase.
                current.visual().title().show(player, current.resolvers());
            }
        }
        double radius = cfg.effects().radius();
        AwakeningSettings.RaceVisual visual = current.visual();
        fx.polygon(player, ground, 0.05, radius, visual.sides(), visual.star(), spin, fx.scale(6, intensity),
                visual.particle(), current.color());
        fx.ring(player, ground, 1.0, radius * 0.75, fx.scale(10, intensity), -spin * 1.5, visual.accent(),
                current.color());
        fx.motes(player, ground, radius, cfg.effects().height(), fx.scale(3, intensity), visual.particle(),
                current.color());
    }

    private void accumulation(Player player, int t, double intensity) {
        AwakeningSettings.Stage stage = cfg.accumulation();
        int length = accumulationEnd - resonanceEnd;
        double p = length == 0 ? 1.0 : (double) (t - resonanceEnd) / length;
        double radius = cfg.effects().radius();
        for (int i = 0; i < resonance.size(); i++) {
            ResonanceStep step = resonance.get(i);
            AwakeningSettings.RaceVisual visual = step.visual();
            double r = radius * (1.0 - 0.45 * p) * (0.7 + 0.1 * i);
            fx.polygon(player, ground, 0.05 + 0.15 * i, r, visual.sides(), visual.star(),
                    spin * (i % 2 == 0 ? 1 : -1), fx.scale(3, intensity), visual.particle(), step.color());
        }
        fx.spiral(player, ground, 0, cfg.effects().height(), radius * 1.2 * (1.0 - 0.5 * p), radius * 0.2, 3,
                fx.scale(10, intensity), 1.5, spin, stage.particle(), NEUTRAL);
        fx.ring(player, ground, 1.0, radius * (1.0 - 0.6 * p) + 0.2, fx.scale(12, intensity), -spin * 2,
                stage.accent(), NEUTRAL);
        if (due(t - resonanceEnd, stage.soundEveryTicks())) {
            stage.sound().play(player, center, (float) (1.0 + p));
        }
    }

    private void collapse(Player player, int t) {
        AwakeningSettings.Stage stage = cfg.collapse();
        int length = collapseEnd - accumulationEnd;
        double p = length == 0 ? 1.0 : (double) (t - accumulationEnd) / length;
        double radius = cfg.effects().radius();
        fx.ring(player, center, 0, radius * (1.0 - p) + 0.1, fx.scale(24, 1), spin * 3, stage.particle(), NEUTRAL);
        fx.spiral(player, ground, 0, cfg.effects().height(), radius * (1.0 - p) + 0.1, 0.1, 2, fx.scale(12, 1),
                1.0, spin * 3, stage.particle(), NEUTRAL);
    }
}
