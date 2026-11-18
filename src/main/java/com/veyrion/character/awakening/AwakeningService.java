package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.race.RaceDefinition;
import com.veyrion.character.race.RaceService;
import com.veyrion.character.race.RollResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Orchestrates THE RIFT AWAKENING. Main thread only.
 *
 * <p>The race is never decided here. {@link RaceService#roll} (the existing transaction, chance calculator and
 * duplicate protection) decides and stores the race on the database thread; only after that future succeeds is a
 * {@link RitualSession} created to show the result. A failed or interrupted ritual can therefore never grant a
 * second roll, and a failed database write never starts a cinematic.
 */
public final class AwakeningService {

    private static final String USE_PERMISSION = "veyrion.awakening.use";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final VeyrionCharacterPlugin plugin;
    private final RaceService races;
    private final AwakeningConfig config;
    private final AltarStore altars;
    private final PendingStore pending;

    /** Players with a running ritual. */
    private final Map<UUID, RitualSession> sessions = new HashMap<>();
    /** Players in the middle of a check or a roll; blocks a second interaction or begin. */
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public AwakeningService(VeyrionCharacterPlugin plugin, RaceService races) {
        this.plugin = plugin;
        this.races = races;
        this.config = new AwakeningConfig(plugin);
        this.altars = new AltarStore(plugin);
        this.pending = new PendingStore(plugin.getLogger(), plugin.getDataFolder());
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    public void start() {
        config.load();
        altars.load();
        pending.load();
    }

    /** Re-reads awakening.yml and altar.yml. Running rituals keep their settings. */
    public void reload() {
        config.load();
        altars.load();
    }

    /** Ends every ritual safely (restores players), then flushes the pending file. Call from onDisable. */
    public void shutdown() {
        for (RitualSession session : new ArrayList<>(sessions.values())) {
            end(session, RitualSession.EndReason.DISABLE, plugin.getServer().getPlayer(session.playerId()));
        }
        sessions.clear();
        busy.clear();
        lastClick.clear();
        pending.close();
    }

    public AwakeningSettings settings() {
        return config.settings();
    }

    public AltarStore altars() {
        return altars;
    }

    public int runningRituals() {
        return sessions.size();
    }

    /** False when awakening.yml sets allow-command-roll to false: /race roll is then refused. */
    public boolean commandRollAllowed() {
        return config.settings().allowCommandRoll();
    }

    // ----------------------------------------------------------------------------------------------- messages

    public void send(CommandSender target, String key, TagResolver... resolvers) {
        AwakeningSettings settings = config.settings();
        target.sendMessage(MINI.deserialize(settings.message("prefix") + settings.message(key), resolvers));
    }

    // ---------------------------------------------------------------------------------------- altar interaction

    /** A player right-clicked the altar block. */
    public void onAltarClick(Player player) {
        AwakeningSettings settings = config.settings();
        UUID id = player.getUniqueId();
        if (!settings.enabled()) {
            send(player, "disabled");
            return;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            send(player, "no-permission");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.get(id);
        if (last != null && now - last < settings.altar().cooldownMs()) {
            return;
        }
        lastClick.put(id, now);
        if (sessions.containsKey(id) || busy.contains(id)) {
            send(player, "busy");
            return;
        }
        busy.add(id);
        races.character(id).whenComplete((character, error) -> {
            try {
                Player online = plugin.getServer().getPlayer(id);
                if (online == null) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to read character of " + online.getName(), error);
                    send(online, "error");
                } else if (character.isEmpty()) {
                    send(online, "not-ready");
                } else if (character.get().hasCompletedGacha()) {
                    send(online, "already-completed");
                } else if (sessions.containsKey(id)) {
                    send(online, "busy");
                } else {
                    AwakeningMenu.open(online, config.settings().gui());
                }
            } finally {
                busy.remove(id);
            }
        });
    }

    /** The player pressed "Begin Awakening". Rolls on the database thread, then shows the result. */
    public void begin(Player player) {
        AwakeningSettings settings = config.settings();
        UUID id = player.getUniqueId();
        if (!settings.enabled()) {
            send(player, "disabled");
            return;
        }
        if (sessions.containsKey(id) || !busy.add(id)) {
            send(player, "busy");
            return;
        }
        boolean started = false;
        try {
            AltarStore.Altar altar = altars.get();
            Location anchor = altar == null ? null : altar.anchor(settings.altar().centerOffsetY());
            if (anchor == null) {
                send(player, "disabled");
                return;
            }
            if (!player.getWorld().equals(anchor.getWorld())
                    || player.getLocation().distanceSquared(anchor) > square(settings.altar().maxStartDistance())) {
                send(player, "too-far");
                return;
            }
            if (player.isInsideVehicle()) {
                send(player, "in-vehicle");
                return;
            }
            started = true;
            races.roll(id).whenComplete((result, error) -> {
                try {
                    onRollFinished(id, altar, result, error);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "Awakening failed after the roll for " + id, e);
                } finally {
                    busy.remove(id);
                }
            });
        } finally {
            if (!started) {
                busy.remove(id);
            }
        }
    }

    private void onRollFinished(UUID id, AltarStore.Altar altar, @Nullable RollResult result,
                                @Nullable Throwable error) {
        Player player = plugin.getServer().getPlayer(id);
        if (error != null || result == null) {
            // The transaction rolled back: nothing was stored, so the player may simply try again.
            plugin.getLogger().log(Level.SEVERE, "Awakening roll failed for " + id, error);
            if (player != null) {
                send(player, "error");
            }
            return;
        }
        switch (result.status()) {
            case ASSIGNED -> showResult(id, player, altar, result);
            case ALREADY_ROLLED -> tell(player, "already-completed");
            case NO_CHARACTER -> tell(player, "not-ready");
            case NO_RACES -> tell(player, "no-races");
            case IN_PROGRESS -> tell(player, "busy");
        }
    }

    private void tell(@Nullable Player player, String key) {
        if (player != null) {
            send(player, key);
        }
    }

    /** The race is stored. Everything from here on is display only. */
    private void showResult(UUID id, @Nullable Player player, AltarStore.Altar altar, RollResult result) {
        String raceId = result.race();
        if (raceId == null) {
            plugin.getLogger().severe("Awakening roll for " + id + " was ASSIGNED without a race id.");
            return;
        }
        RaceDefinition race = plugin.races().settings().find(raceId);
        if (race == null) {
            race = new RaceDefinition(raceId, raceId, NamedTextColor.WHITE, "", 1.0, true);
        }
        // Remember the unfinished reveal first, so a disconnect or crash can still tell the player on rejoin.
        pending.put(id, new PendingStore.Entry(race.id(), result.chance(), null, null));
        if (player == null || !player.isOnline() || !plugin.isEnabled()) {
            return;
        }
        AwakeningSettings settings = config.settings();
        Location ground = altar.anchor(0.05);
        Location center = altar.anchor(settings.altar().centerOffsetY());
        if (ground == null || center == null) {
            deliverWithoutCinematic(player, id, race, result.chance(), "interrupted");
            return;
        }
        RitualSession session = null;
        try {
            session = new RitualSession(plugin, this, player, settings, ground, center, race, result.chance());
            sessions.put(id, session);
            session.start(player);
            if (session.originalWalkSpeed() != null || session.originalFlySpeed() != null) {
                pending.put(id, new PendingStore.Entry(race.id(), result.chance(),
                        session.originalWalkSpeed(), session.originalFlySpeed()));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the awakening cinematic for " + player.getName(), e);
            if (session != null) {
                end(session, RitualSession.EndReason.ERROR, player);
            } else {
                deliverWithoutCinematic(player, id, race, result.chance(), "interrupted");
            }
        }
    }

    private void deliverWithoutCinematic(Player player, UUID id, RaceDefinition race, double chance, String key) {
        send(player, key, RitualSession.resolversFor(race.displayName(), race.description(), race.color(),
                player.getName(), chance));
        pending.remove(id);
    }

    // -------------------------------------------------------------------------------------------------- ending

    /**
     * Ends a ritual. Idempotent. The race stays saved in every case; this only cleans up and tells the player.
     *
     * @param player the player if still reachable, else null
     */
    void end(RitualSession session, RitualSession.EndReason reason, @Nullable Player player) {
        if (session.ended()) {
            return;
        }
        UUID id = session.playerId();
        sessions.remove(id, session);
        Float walk = session.originalWalkSpeed();
        Float fly = session.originalFlySpeed();
        session.close(player, reason != RitualSession.EndReason.COMPLETED);
        // If the player could not be reached, keep the speeds in the pending file so the next join restores them.
        boolean restored = player != null || (walk == null && fly == null);
        PendingStore.Entry kept = new PendingStore.Entry(session.raceId(), session.chance(),
                restored ? null : walk, restored ? null : fly);
        switch (reason) {
            case COMPLETED -> {
                pending.remove(id);
                if (player != null) {
                    send(player, "completed", session.raceResolvers());
                }
            }
            case QUIT, DISABLE -> pending.put(id, kept);
            case DEATH, LEASH, WORLD, ERROR -> {
                if (player != null) {
                    send(player, "interrupted", session.raceResolvers());
                    Component lore = session.lore();
                    if (lore != null) {
                        player.sendMessage(lore);
                    }
                    pending.remove(id);
                } else {
                    pending.put(id, kept);
                }
            }
        }
    }

    void end(RitualSession session, RitualSession.EndReason reason) {
        end(session, reason, plugin.getServer().getPlayer(session.playerId()));
    }

    // ---------------------------------------------------------------------------------------- event entry points

    @Nullable RitualSession session(UUID id) {
        return sessions.get(id);
    }

    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        RitualSession session = sessions.get(id);
        if (session != null) {
            end(session, RitualSession.EndReason.QUIT, player);
        }
        busy.remove(id);
        lastClick.remove(id);
    }

    public void onDeath(Player player) {
        RitualSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            end(session, RitualSession.EndReason.DEATH, player);
        }
    }

    public void onWorldUnload(World world) {
        for (RitualSession session : new ArrayList<>(sessions.values())) {
            if (session.worldId().equals(world.getUID())) {
                end(session, RitualSession.EndReason.WORLD);
            }
        }
    }

    /** Restores speeds after a crash and tells the player the race the Rift chose while they were away. */
    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        PendingStore.Entry entry = pending.get(id);
        if (entry == null || sessions.containsKey(id)) {
            return;
        }
        if (entry.walkSpeed() != null && validSpeed(entry.walkSpeed())) {
            player.setWalkSpeed(entry.walkSpeed());
        }
        if (entry.flySpeed() != null && validSpeed(entry.flySpeed())) {
            player.setFlySpeed(entry.flySpeed());
        }
        if (entry.walkSpeed() != null || entry.flySpeed() != null) {
            pending.put(id, new PendingStore.Entry(entry.race(), entry.chance(), null, null));
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PendingStore.Entry current = pending.get(id);
            Player online = plugin.getServer().getPlayer(id);
            if (current == null || online == null || sessions.containsKey(id)) {
                return;
            }
            RaceDefinition race = plugin.races().settings().find(current.race());
            String name = race != null ? race.displayName() : current.race();
            TextColor color = race != null ? race.color() : NamedTextColor.WHITE;
            send(online, "welcome-back", RitualSession.resolversFor(name, race != null ? race.description() : "",
                    color, online.getName(), current.chance()));
            pending.remove(id);
        }, 40L);
    }

    private static boolean validSpeed(float speed) {
        return Float.isFinite(speed) && speed >= -1f && speed <= 1f;
    }

    private static double square(double value) {
        return value * value;
    }
}
