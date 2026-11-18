package com.veyrion.character.core;

import com.veyrion.character.awakening.AwakeningListener;
import com.veyrion.character.awakening.AwakeningService;
import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.character.ExpResult;
import com.veyrion.character.character.ExpService;
import com.veyrion.character.command.CharacterCommand;
import com.veyrion.character.command.QuestAdminCommand;
import com.veyrion.character.command.QuestCommand;
import com.veyrion.character.command.RaceAdminCommand;
import com.veyrion.character.command.RaceCommand;
import com.veyrion.character.config.ConfigManager;
import com.veyrion.character.config.Messages;
import com.veyrion.character.database.DatabaseException;
import com.veyrion.character.database.DatabaseManager;
import com.veyrion.character.listener.PlayerJoinListener;
import com.veyrion.character.listener.QuestListener;
import com.veyrion.character.quest.QuestRegistry;
import com.veyrion.character.quest.QuestRepository;
import com.veyrion.character.quest.QuestService;
import com.veyrion.character.race.RaceRegistry;
import com.veyrion.character.race.RaceRepository;
import com.veyrion.character.race.RaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class VeyrionCharacterPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private Messages messages;
    private DatabaseManager databaseManager;
    private CharacterRepository characterRepository;
    private ExpService expService;
    private QuestRegistry questRegistry;
    private QuestService questService;
    private RaceRegistry raceRegistry;
    private RaceService raceService;
    private AwakeningService awakeningService;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        messages = new Messages(this);
        messages.load(configManager.settings().language());

        raceRegistry = new RaceRegistry(this);
        raceRegistry.load();

        if (!startDatabase()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        expService = new ExpService(characterRepository, () -> configManager.settings().ranks());

        questRegistry = new QuestRegistry(this);
        questRegistry.reload();
        questService = new QuestService(this, questRegistry, new QuestRepository(databaseManager),
                characterRepository, expService);

        raceService = new RaceService(this, raceRegistry, new RaceRepository(databaseManager), characterRepository);

        awakeningService = new AwakeningService(this, raceService);
        awakeningService.start();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, characterRepository), this);
        getServer().getPluginManager().registerEvents(new AwakeningListener(this, awakeningService), this);
        getServer().getPluginManager().registerEvents(new QuestListener(this, questService), this);

        register("veyrioncharacter", new CharacterCommand(this, characterRepository, expService));
        register("quest", new QuestCommand(this, questService));
        register("questadmin", new QuestAdminCommand(this, questService));
        register("race", new RaceCommand(this, raceService));
        register("raceadmin", new RaceAdminCommand(this, raceService, awakeningService));

        long interval = configManager.settings().quests().exploreCheckIntervalTicks();
        getServer().getScheduler().runTaskTimer(this, questService::exploreTick, interval, interval);

        // Players already online (e.g. after /reload) are not covered by the join event.
        getServer().getOnlinePlayers().forEach(questService::load);

        getLogger().info("VeyrionCharacter " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // End rituals first: players get their movement back and the already stored race is kept.
        if (awakeningService != null) {
            awakeningService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private void register(String name, TabExecutor handler) {
        var command = Objects.requireNonNull(getCommand(name), "Command '" + name + "' is missing from plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private boolean startDatabase() {
        var settings = configManager.settings().database();
        try {
            Path dataFolder = getDataFolder().toPath();
            Files.createDirectories(dataFolder);
            databaseManager = new DatabaseManager(getLogger(), dataFolder.resolve(settings.file()),
                    settings.busyTimeoutMs());
            databaseManager.initialize();
            characterRepository = new CharacterRepository(databaseManager);
            return true;
        } catch (IOException | DatabaseException e) {
            getLogger().log(Level.SEVERE, "Could not start the database. Disabling VeyrionCharacter.", e);
            return false;
        }
    }

    /** Re-reads config.yml, language files, races.yml and quest files. Never touches stored character data. */
    public void reloadSettings() {
        configManager.load();
        messages.load(configManager.settings().language());
        raceRegistry.load();
        questRegistry.reload();
        if (awakeningService != null) {
            awakeningService.reload();
        }
    }

    /** Tells an online player about gained EXP and rank-ups. Safe to call from any thread. */
    public void announceExp(UUID uuid, ExpResult result, long gained) {
        if (!isEnabled()) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            Player player = getServer().getPlayer(uuid);
            if (player == null) {
                return;
            }
            messages.send(player, "exp.gain", Placeholder.unparsed("amount", Long.toString(gained)));
            if (result.rankedUp()) {
                messages.send(player, "rank.up", Placeholder.unparsed("rank", result.newRank()));
            }
        });
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public Messages messages() {
        return messages;
    }

    public RaceRegistry races() {
        return raceRegistry;
    }

    /** The Rift Awakening altar system; null only before the plugin finished enabling. */
    public AwakeningService awakening() {
        return awakeningService;
    }

    public void debug(String message) {
        if (configManager.settings().debug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
