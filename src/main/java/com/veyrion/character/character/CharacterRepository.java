package com.veyrion.character.character;

import com.veyrion.character.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Basic persistence operations for {@link CharacterData}. Every method runs on the database thread and
 * returns a future; callbacks therefore must not touch the Bukkit API directly.
 */
public final class CharacterRepository {

    private static final String SELECT_BY_UUID = """
            SELECT uuid, player_name, race, adventure_exp, adventure_rank, created_at, has_completed_gacha
            FROM characters WHERE uuid = ?
            """;

    private static final String INSERT_IF_ABSENT = """
            INSERT OR IGNORE INTO characters
                (uuid, player_name, race, adventure_exp, adventure_rank, created_at, has_completed_gacha)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /** Upsert that never overwrites {@code created_at}. */
    private static final String UPSERT = """
            INSERT INTO characters
                (uuid, player_name, race, adventure_exp, adventure_rank, created_at, has_completed_gacha)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                race = excluded.race,
                adventure_exp = excluded.adventure_exp,
                adventure_rank = excluded.adventure_rank,
                has_completed_gacha = excluded.has_completed_gacha
            """;

    private static final String UPDATE_NAME = "UPDATE characters SET player_name = ? WHERE uuid = ?";
    private static final String DELETE_BY_UUID = "DELETE FROM characters WHERE uuid = ?";

    private final DatabaseManager database;

    public CharacterRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Adds EXP and recalculates the rank in one transaction. Fails if the character does not exist. */
    public CompletableFuture<ExpResult> addExp(UUID uuid, long amount, RankTable ranks) {
        return database.inTransaction(connection -> applyExp(connection, uuid, amount, ranks));
    }

    /**
     * Adds EXP using an existing connection so callers (e.g. quest completion) can include it in their own
     * transaction. Must run on the database thread.
     */
    public static ExpResult applyExp(Connection connection, UUID uuid, long amount, RankTable ranks)
            throws SQLException {
        long oldExp;
        String oldRank;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT adventure_exp, adventure_rank FROM characters WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("No character exists for " + uuid);
                }
                oldExp = resultSet.getLong(1);
                oldRank = resultSet.getString(2);
            }
        }

        long newExp;
        try {
            newExp = Math.addExact(oldExp, amount);
        } catch (ArithmeticException e) {
            newExp = Long.MAX_VALUE;
        }
        String newRank = ranks.rankFor(newExp).name();

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE characters SET adventure_exp = ?, adventure_rank = ? WHERE uuid = ?")) {
            update.setLong(1, newExp);
            update.setString(2, newRank);
            update.setString(3, uuid.toString());
            update.executeUpdate();
        }
        return new ExpResult(oldExp, newExp, oldRank, newRank);
    }

    public CompletableFuture<Optional<CharacterData>> find(UUID uuid) {
        return database.supply(connection -> select(connection, uuid));
    }

    /**
     * Returns the stored character for {@code template.uuid()}, inserting {@code template} first if none
     * exists. An existing character is never overwritten, except that a changed player name is refreshed.
     */
    public CompletableFuture<CharacterData> findOrCreate(CharacterData template) {
        return database.supply(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_IF_ABSENT)) {
                bind(insert, template);
                insert.executeUpdate();
            }
            CharacterData stored = select(connection, template.uuid())
                    .orElseThrow(() -> new SQLException("Character row missing after insert: " + template.uuid()));

            if (stored.playerName().equals(template.playerName())) {
                return stored;
            }
            try (PreparedStatement update = connection.prepareStatement(UPDATE_NAME)) {
                update.setString(1, template.playerName());
                update.setString(2, stored.uuid().toString());
                update.executeUpdate();
            }
            return stored.withPlayerName(template.playerName());
        });
    }

    /** Inserts or updates a character. {@code created_at} of an existing row is preserved. */
    public CompletableFuture<Void> save(CharacterData data) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                bind(statement, data);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** @return a future holding {@code true} if a character was deleted */
    public CompletableFuture<Boolean> delete(UUID uuid) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_BY_UUID)) {
                statement.setString(1, uuid.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static Optional<CharacterData> select(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_UUID)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static void bind(PreparedStatement statement, CharacterData data) throws SQLException {
        statement.setString(1, data.uuid().toString());
        statement.setString(2, data.playerName());
        if (data.race() == null) {
            statement.setNull(3, Types.VARCHAR);
        } else {
            statement.setString(3, data.race());
        }
        statement.setLong(4, data.adventureExp());
        statement.setString(5, data.adventureRank());
        statement.setLong(6, data.createdAt().toEpochMilli());
        statement.setInt(7, data.hasCompletedGacha() ? 1 : 0);
    }

    private static CharacterData map(ResultSet resultSet) throws SQLException {
        return new CharacterData(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("race"),
                resultSet.getLong("adventure_exp"),
                resultSet.getString("adventure_rank"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                resultSet.getInt("has_completed_gacha") != 0);
    }
}
