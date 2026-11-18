package com.veyrion.character.race;

import com.veyrion.character.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;

/**
 * Race persistence on the existing {@code characters} table ({@code race}, {@code has_completed_gacha}).
 * Every method runs on the database thread and returns a future; callbacks must not touch the Bukkit API.
 */
public final class RaceRepository {

    private static final String SELECT_STATE = "SELECT race, has_completed_gacha FROM characters WHERE uuid = ?";

    /** The claim. The {@code has_completed_gacha = 0} condition makes it succeed at most once per character. */
    private static final String CLAIM =
            "UPDATE characters SET race = ?, has_completed_gacha = 1 WHERE uuid = ? AND has_completed_gacha = 0";

    private static final String POPULATIONS = """
            SELECT LOWER(race), COUNT(*) FROM characters
            WHERE race IS NOT NULL AND race <> ''
            GROUP BY LOWER(race)
            """;

    private final DatabaseManager database;

    public RaceRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Rolls and stores a race in ONE transaction on the single database thread: read populations, compute
     * chances, pick, then claim with a conditional UPDATE. Two rolls for the same character can never both win.
     */
    public CompletableFuture<RollResult> roll(UUID uuid, RaceSettings settings, RandomGenerator random) {
        return database.inTransaction(connection -> claim(connection, uuid, settings, random));
    }

    private static RollResult claim(Connection connection, UUID uuid, RaceSettings settings, RandomGenerator random)
            throws SQLException {
        String existingRace;
        try (PreparedStatement select = connection.prepareStatement(SELECT_STATE)) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return RollResult.of(RollResult.Status.NO_CHARACTER);
                }
                existingRace = resultSet.getString(1);
                if (resultSet.getInt(2) != 0) {
                    return RollResult.alreadyRolled(existingRace);
                }
            }
        }

        List<RaceChanceCalculator.Entry> entries = settings.entries();
        if (entries.isEmpty()) {
            return RollResult.of(RollResult.Status.NO_RACES);
        }
        List<RaceChanceCalculator.Chance> chances =
                RaceChanceCalculator.compute(entries, populations(connection), settings.params());
        RaceChanceCalculator.Chance picked = RaceChanceCalculator.pick(chances, random);

        try (PreparedStatement update = connection.prepareStatement(CLAIM)) {
            update.setString(1, picked.id());
            update.setString(2, uuid.toString());
            if (update.executeUpdate() == 0) {
                // Lost a race with another writer: report what the winner stored.
                return RollResult.alreadyRolled(currentRace(connection, uuid));
            }
        }
        return RollResult.assigned(picked.id(), picked.chance());
    }

    /** Admin: sets the race and marks the gacha as completed so the player cannot roll over it. */
    public CompletableFuture<Boolean> setRace(UUID uuid, String raceId) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE characters SET race = ?, has_completed_gacha = 1 WHERE uuid = ?")) {
                statement.setString(1, raceId);
                statement.setString(2, uuid.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Admin: clears the race and the gacha flag so the player can roll again. */
    public CompletableFuture<Boolean> reset(UUID uuid) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE characters SET race = NULL, has_completed_gacha = 0 WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Characters per race (lower-case id), counted over the whole table, online or not. */
    public CompletableFuture<Map<String, Long>> populations() {
        return database.supply(RaceRepository::populations);
    }

    private static Map<String, Long> populations(Connection connection) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(POPULATIONS);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.put(resultSet.getString(1), resultSet.getLong(2));
            }
        }
        return result;
    }

    private static String currentRace(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_STATE)) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }
}
