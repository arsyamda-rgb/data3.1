package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Bukkit events for the Rift Awakening. All logic lives in {@link AwakeningService}. */
public final class AwakeningListener implements Listener {

    private final VeyrionCharacterPlugin plugin;
    private final AwakeningService service;

    public AwakeningListener(VeyrionCharacterPlugin plugin, AwakeningService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * Runs even if another plugin (a claim, for example) denied the click, so the altar also works inside
     * protected spawn areas. Only the configured altar block is affected.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        AltarStore.Altar altar = service.altars().get();
        if (block == null || altar == null || !altar.matches(block)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            service.onAltarClick(event.getPlayer());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!AwakeningMenu.isMenu(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !AwakeningMenu.isMenu(event.getClickedInventory())) {
            return;
        }
        int slot = event.getSlot();
        if (slot == AwakeningMenu.SLOT_BEGIN) {
            // Next tick: never close or replace an inventory from inside its own click event.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                service.begin(player);
            });
        } else if (slot == AwakeningMenu.SLOT_CANCEL) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                service.send(player, "cancelled");
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (AwakeningMenu.isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        RitualSession session = service.session(event.getPlayer().getUniqueId());
        if (session == null || !session.locksMovement()) {
            return;
        }
        // Keep the head free: restore only the position.
        Location from = event.getFrom();
        Location to = event.getTo();
        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RitualSession session = service.session(player.getUniqueId());
        if (session != null && session.protectsFromDamage()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        service.onDeath(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        service.onWorldUnload(event.getWorld());
    }
}
