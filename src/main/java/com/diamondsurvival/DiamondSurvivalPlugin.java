package com.diamondsurvival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

public class DiamondSurvivalPlugin extends JavaPlugin {

    private static DiamondSurvivalPlugin instance;
    private DiamondManager diamondManager;
    private OverlayServer overlayServer;
    private GameCommands gameCommands;
    private TrackerClient tracker;

    // Game state
    private boolean gameRunning = false;

    // Countdown state
    private int countdownTask = -1;
    private int countdownRemaining = -1;
    private int countdownTotal = 60;
    private boolean dramaticPhase = false;
    private int countdownGen = 0;

    // Death drop toggle
    private boolean deathDropItems = false;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        saveResource("KOMIKAX_.ttf", false);

        // Initialize diamond manager
        diamondManager = new DiamondManager(this);
        diamondManager.load();

        // Initialize overlay server
        int port = getConfig().getInt("overlay-port", 6971);
        overlayServer = new OverlayServer(this, port);
        overlayServer.start();

        // Register commands
        gameCommands = new GameCommands(this);
        MbdsvCommand mbdsvCmd = new MbdsvCommand(gameCommands);
        getCommand("mbdsv").setExecutor(mbdsvCmd);
        getCommand("mbdsv").setTabCompleter(mbdsvCmd);

        // Initialize tracker
        tracker = new TrackerClient(this);
        tracker.pingAsync("server_start");
        tracker.startHeartbeat();

        // Register listener
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);

        // Periodic diamond inventory sync + countdown check (every 0.5s)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!gameRunning) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                diamondManager.syncInventory(p);
            }
            maybeStartCountdown();
        }, 20L, 10L);

        getLogger().info("DiamondSurvival v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        if (diamondManager != null) diamondManager.save();
        if (overlayServer != null) overlayServer.stop();
        if (tracker != null) tracker.stop();
        getLogger().info("DiamondSurvival disabled.");
    }

    // ─── Game Start/Stop ──────────────────────────────────────────────

    public void startGame() {
        gameRunning = true;
        stopCountdown();
        World world = Bukkit.getWorlds().get(0);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player p : Bukkit.getOnlinePlayers()) {
            // Reset diamonds
            diamondManager.setDiamonds(p.getUniqueId(), 0);
            // Clear inventory
            p.getInventory().clear();
            p.setExp(0);
            p.setLevel(0);
            // Teleport 1000 blocks away in random direction
            Location cur = p.getLocation();
            double angle = rng.nextDouble() * 2 * Math.PI;
            int nx = cur.getBlockX() + (int)(Math.cos(angle) * 1000);
            int nz = cur.getBlockZ() + (int)(Math.sin(angle) * 1000);
            int ny = world.getHighestBlockYAt(nx, nz) + 1;
            p.teleport(new Location(world, nx + 0.5, ny, nz + 0.5));
            // Effects
            applyGameEffects(p);
            p.sendTitle("§a§lเกมเริ่มแล้ว!", "§fหาเพชรให้ครบเป้า!", 10, 60, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        }
    }

    public void stopGame() {
        gameRunning = false;
        stopCountdown();
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeGameEffects(p);
            p.sendTitle("§c§lเกมหยุดแล้ว!", "§fระบบถูกปิด", 10, 60, 20);
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1f);
        }
    }

    public void applyGameEffects(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    public void removeGameEffects(Player player) {
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    // ─── Countdown ────────────────────────────────────────────────────

    public void maybeStartCountdown() {
        if (!gameRunning) return;
        int total = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            total += diamondManager.getDiamonds(p.getUniqueId());
        }

        if (total >= diamondManager.getGoal()) {
            if (countdownTask < 0 && !dramaticPhase) {
                countdownTotal = getConfig().getInt("countdown-seconds", 60);
                countdownRemaining = countdownTotal;
                countdownTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                    if (countdownRemaining < 0) return;
                    if (countdownRemaining == 0) {
                        onCountdownEnd();
                        return;
                    }
                    if (countdownRemaining <= 3) {
                        int rem = countdownRemaining;
                        Bukkit.getScheduler().cancelTask(countdownTask);
                        countdownTask = -1;
                        startDramaticCountdown(rem);
                        return;
                    }
                    String color = countdownRemaining > countdownTotal * 2 / 3 ? "§a"
                            : countdownRemaining > countdownTotal / 3 ? "§e" : "§c";
                    Sound tickSound = countdownRemaining <= countdownTotal / 3
                            ? Sound.BLOCK_NOTE_BLOCK_BASS
                            : Sound.BLOCK_NOTE_BLOCK_HAT;
                    float tickPitch = countdownRemaining <= countdownTotal / 3 ? 1.5f : 1.0f;
                    String title = color + countdownRemaining;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(title, "§fจะชนะในอีก", 0, 25, 5);
                        p.playSound(p.getLocation(), tickSound, 1f, tickPitch);
                    }
                    countdownRemaining--;
                }, 0L, 20L);
            }
        } else {
            stopCountdown();
        }
    }

    private void onCountdownEnd() {
        if (countdownTask >= 0) {
            Bukkit.getScheduler().cancelTask(countdownTask);
            countdownTask = -1;
        }
        countdownRemaining = -1;
        dramaticPhase = false;

        // Reset diamonds
        for (Player p : Bukkit.getOnlinePlayers()) {
            diamondManager.setDiamonds(p.getUniqueId(), 0);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§6§lชนะแล้ว!", "§fกำลังส่งไปเกิดใหม่...", 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.5f);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            World world = Bukkit.getWorlds().get(0);
            Location spawn = world.getSpawnLocation();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int spread = 200;

            for (Player p : Bukkit.getOnlinePlayers()) {
                int rx = spawn.getBlockX() + rng.nextInt(-spread, spread);
                int rz = spawn.getBlockZ() + rng.nextInt(-spread, spread);
                int ry = world.getHighestBlockYAt(rx, rz) + 1;
                p.teleport(new Location(world, rx + 0.5, ry, rz + 0.5));
                p.clearTitle();
            }
        }, 100L);
    }

    private void startDramaticCountdown(int from) {
        dramaticPhase = true;
        final int gen = countdownGen;
        long delay = 0;
        for (int n = from; n >= 1; n--) {
            final int num = n;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (gen != countdownGen) return;
                showDramatic(num);
            }, delay);
            delay += (n == 1) ? 60L : 40L;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (gen != countdownGen) return;
            onCountdownEnd();
        }, delay);
    }

    private void showDramatic(int num) {
        float pitch = num == 1 ? 0.5f : num == 2 ? 0.8f : 1.1f;
        int stay = num == 1 ? 65 : 45;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§c§l" + num, "§fจะชนะในอีก", 5, stay, 5);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, pitch);
        }
    }

    public void stopCountdown() {
        countdownGen++;
        if (countdownTask >= 0) {
            Bukkit.getScheduler().cancelTask(countdownTask);
            countdownTask = -1;
        }
        if (countdownRemaining >= 0 || dramaticPhase) {
            countdownRemaining = -1;
            dramaticPhase = false;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.clearTitle();
            }
        }
    }

    // ─── Getters ──────────────────────────────────────────────────────

    public static DiamondSurvivalPlugin getInstance() { return instance; }
    public DiamondManager getDiamondManager() { return diamondManager; }
    public OverlayServer getOverlayServer() { return overlayServer; }
    public GameCommands getGameCommands() { return gameCommands; }
    public boolean isGameRunning() { return gameRunning; }
    public boolean isDeathDropItems() { return deathDropItems; }
    public void setDeathDropItems(boolean value) { deathDropItems = value; }
    public TrackerClient getTracker() { return tracker; }
    public int getCountdownRemaining() { return countdownRemaining; }
}
