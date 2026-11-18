package com.veyrion.character.listener;

import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Makes sure every player who joins has a character row. */
public final class PlayerJoinListener implements Listener {

    private final VeyrionCharacterPlugin plugin;
    private final CharacterRepository repository;

    public PlayerJoinListener(VeyrionCharacterPlugin plugin, CharacterRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        var template = plugin.configManager().settings().defaultCharacter().create(uuid, name);
        // Completes on the database thread: only log here, never touch the Bukkit API.
        repository.findOrCreate(template).whenComplete((character, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load or create character for " + name + " (" + uuid + ")", error);
            } else {
                plugin.debug("Character ready for " + name + ": rank=" + character.adventureRank()
                        + ", exp=" + character.adventureExp());
            }
        });
    }
}
