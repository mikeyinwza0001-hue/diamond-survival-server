package com.diamondsurvival;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import net.kyori.adventure.text.Component;

public class GameListener implements Listener {

    private final DiamondSurvivalPlugin plugin;

    public GameListener(DiamondSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Access Control: check tracker status on join ───────────────
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        // Fail-closed: reject any status other than explicitly "approved".
        TrackerClient tc = plugin.getTracker();
        if (tc == null || !tc.isApproved()) {
            final String s = tc == null ? "missing" : tc.getAccessStatus();
            Bukkit.getScheduler().runTask(plugin, () ->
                player.kick(Component.text(
                    "§cเซิร์ฟเวอร์ไม่สามารถใช้งานได้\n" +
                    "§7สถานะ: §c§l" + s + "\n" +
                    "§eกรุณาติดต่อ Admin")));
            return;
        }

        // Track player join
        if (plugin.getTracker() != null) {
            plugin.getTracker().sendPlayerEvent("join", player);
        }

        // Auto-start game if not running, otherwise apply effects
        if (!plugin.isGameRunning()) {
            plugin.startGame();
        } else {
            plugin.applyGameEffects(player);
        }
    }

    // ─── Player Quit: track to mabel-tracker ────────────────────────
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (plugin.getTracker() != null) {
            plugin.getTracker().sendPlayerEvent("quit", e.getPlayer());
        }
    }

    // ─── Respawn: re-apply effects ────────────────────────────────────
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        if (plugin.isGameRunning()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.applyGameEffects(e.getPlayer()), 2L);
        }
    }

    // ─── Keep food full when game is running ──────────────────────────
    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (plugin.isGameRunning() && e.getEntity() instanceof Player) {
            e.setCancelled(true);
        }
    }

    // ─── Death: keep inventory or drop items ──────────────────────────
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!plugin.isGameRunning()) return;
        if (!plugin.isDeathDropItems()) {
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    // ─── Drop diamond = remove from counter ───────────────────────────
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (!plugin.isGameRunning()) return;
        if (e.getItemDrop().getItemStack().getType() == Material.DIAMOND) {
            int amount = e.getItemDrop().getItemStack().getAmount();
            e.setCancelled(true);
            plugin.getDiamondManager().removeDiamonds(e.getPlayer().getUniqueId(), amount);
        }
    }

    // ─── Block break: diamond tracking ─────────────────────────────
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.isCancelled() || !plugin.isGameRunning()) return;
        Player player = e.getPlayer();
        Block block = e.getBlock();
        Material type = block.getType();
        boolean isDiamond = (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE);

        if (isDiamond) {
            countDiamond(player);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private void countDiamond(Player player) {
        DiamondManager dm = plugin.getDiamondManager();
        int before = dm.getDiamonds(player.getUniqueId());
        dm.addDiamonds(player.getUniqueId(), 1);
        int after = dm.getDiamonds(player.getUniqueId());
        int goal = dm.getGoal();

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.8f);

        // Win sound (once)
        if (after >= goal && before < goal) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

}
