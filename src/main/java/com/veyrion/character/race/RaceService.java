package com.veyrion.character.race;

import com.veyrion.character.character.CharacterData;
import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Race rules. Every future returned here completes on the Minecraft main thread, so callers may use the
 * Bukkit API directly in their callbacks. Database work itself runs on the database thread.
 */
public final class RaceService {

    /** Current chances plus how many characters were counted. */
    public record ChanceReport(List<RaceChanceCalculator.Chance> chances, long counted, long other,
                               boolean dynamic) {}

    private final VeyrionCharacterPlugin plugin;
    private final RaceRegistry registry;
    private final RaceRepository repository;
    private final CharacterRepository characters;
    private final SecureRandom random = new SecureRandom();
    /** Players with a roll in flight. Main thread only. */
    private final Set<UUID> rolling = new HashSet<>();

    public RaceService(VeyrionCharacterPlugin plugin, RaceRegistry registry, RaceRepository repository,
                       CharacterRepository characters) {
        this.plugin = plugin;
        this.registry = registry;
        this.repository = repository;
        this.characters = characters;
    }

    public RaceRegistry registry() {
        return registry;
    }

    /** Call from the main thread. A second call while one is running returns IN_PROGRESS without touching the database. */
    public CompletableFuture<RollResult> roll(UUID uuid) {
        if (!rolling.add(uuid)) {
            return CompletableFuture.completedFuture(RollResult.of(RollResult.Status.IN_PROGRESS));
        }
        RaceSettings snapshot = registry.settings();
        return toMain(repository.roll(uuid, snapshot, random)).whenComplete((result, error) -> rolling.remove(uuid));
    }

    /** @return true if the character exists and was updated */
    public CompletableFuture<Boolean> setRace(UUID uuid, String raceId) {
        return toMain(repository.setRace(uuid, raceId));
    }

    /** @return true if the character exists and was reset */
    public CompletableFuture<Boolean> reset(UUID uuid) {
        return toMain(repository.reset(uuid));
    }

    public CompletableFuture<Optional<CharacterData>> character(UUID uuid) {
        return toMain(characters.find(uuid));
    }

    /** Current chances for every enabled race, from live populations. */
    public CompletableFuture<ChanceReport> chances() {
        RaceSettings snapshot = registry.settings();
        List<RaceChanceCalculator.Entry> entries = snapshot.entries();
        CompletableFuture<ChanceReport> computed = repository.populations().thenApply(populations -> {
            if (entries.isEmpty()) {
                return new ChanceReport(List.of(), 0, 0, snapshot.dynamic().enabled());
            }
            List<RaceChanceCalculator.Chance> chances =
                    RaceChanceCalculator.compute(entries, populations, snapshot.params());
            long counted = chances.stream().mapToLong(RaceChanceCalculator.Chance::population).sum();
            long all = populations.values().stream().mapToLong(Long::longValue).sum();
            return new ChanceReport(chances, counted, all - counted, snapshot.dynamic().enabled());
        });
        return toMain(computed);
    }

    private <T> CompletableFuture<T> toMain(CompletableFuture<T> source) {
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(value);
                }
            });
        });
        return result;
    }
}
