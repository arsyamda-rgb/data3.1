package com.veyrion.character.config;

import com.veyrion.character.character.CharacterData;
import com.veyrion.character.character.RankTable;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated, immutable view of {@code config.yml}. Invalid values fall back to defaults with a warning. */
public record PluginSettings(
        String language,
        Database database,
        boolean debug,
        DefaultCharacter defaultCharacter,
        RankTable ranks,
        Quests quests) {

    public record Database(String file, int busyTimeoutMs) {}

    public record Quests(int exploreCheckIntervalTicks, boolean allowAbandon) {}

    public record DefaultCharacter(long adventureExp, String adventureRank, boolean hasCompletedGacha) {

        /** Builds a brand-new character using these defaults. The race starts unassigned. */
        public CharacterData create(UUID uuid, String playerName) {
            return new CharacterData(uuid, playerName, null, adventureExp, adventureRank,
                    Instant.now(), hasCompletedGacha);
        }
    }

    static PluginSettings from(FileConfiguration config, Logger logger) {
        String language = config.getString("language", "en").trim().toLowerCase(Locale.ROOT);
        if (language.isEmpty()) {
            logger.warning("config.yml: 'language' is empty; using 'en'.");
            language = "en";
        }

        String file = config.getString("database.file", "characters.db").trim();
        if (!isPlainFileName(file)) {
            logger.warning("config.yml: 'database.file' must be a plain file name; using 'characters.db'.");
            file = "characters.db";
        }

        int busyTimeout = config.getInt("database.busy-timeout-ms", 5000);
        if (busyTimeout < 0) {
            logger.warning("config.yml: 'database.busy-timeout-ms' must not be negative; using 5000.");
            busyTimeout = 5000;
        }

        RankTable ranks = parseRanks(config, logger);

        long exp = config.getLong("default-character.adventure-exp", 0);
        if (exp < 0) {
            logger.warning("config.yml: 'default-character.adventure-exp' must not be negative; using 0.");
            exp = 0;
        }

        String rank = config.getString("default-character.adventure-rank", "Novice").trim();
        int rankIndex = ranks.indexOf(rank);
        if (rankIndex < 0) {
            String first = ranks.ranks().get(0).name();
            logger.warning("config.yml: 'default-character.adventure-rank' ('" + rank
                    + "') is not in 'adventure-ranks'; using '" + first + "'.");
            rank = first;
        } else {
            rank = ranks.ranks().get(rankIndex).name();
        }

        int interval = config.getInt("quests.explore-check-interval-ticks", 20);
        if (interval < 5 || interval > 200) {
            logger.warning("config.yml: 'quests.explore-check-interval-ticks' must be 5-200; using 20.");
            interval = 20;
        }

        return new PluginSettings(
                language,
                new Database(file, busyTimeout),
                config.getBoolean("debug", false),
                new DefaultCharacter(exp, rank, config.getBoolean("default-character.has-completed-gacha", false)),
                ranks,
                new Quests(interval, config.getBoolean("quests.allow-abandon", true)));
    }

    private static RankTable parseRanks(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> raw = config.getMapList("adventure-ranks");
        if (raw.isEmpty()) {
            logger.warning("config.yml: 'adventure-ranks' is missing or empty; using built-in defaults.");
            return RankTable.defaults();
        }
        try {
            List<RankTable.Rank> parsed = new ArrayList<>();
            for (Map<?, ?> entry : raw) {
                Object name = entry.get("name");
                Object exp = entry.get("exp");
                if (!(name instanceof String) || ((String) name).isBlank() || !(exp instanceof Number)) {
                    throw new IllegalArgumentException("each rank needs a 'name' and a numeric 'exp'");
                }
                parsed.add(new RankTable.Rank(((String) name).trim(), ((Number) exp).longValue()));
            }
            return new RankTable(parsed);
        } catch (IllegalArgumentException e) {
            logger.warning("config.yml: invalid 'adventure-ranks' (" + e.getMessage() + "); using built-in defaults.");
            return RankTable.defaults();
        }
    }

    private static boolean isPlainFileName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        try {
            Path path = Path.of(name);
            return path.getNameCount() == 1 && !path.isAbsolute() && !name.equals("..");
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
