package com.diamondsurvival;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class GameCommands {

    private final DiamondSurvivalPlugin plugin;
    private final DiamondManager dm;

    public GameCommands(DiamondSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.dm = plugin.getDiamondManager();
    }

    // ─── /mbdsv start ─────────────────────────────────────────────────
    public boolean start(CommandSender sender) {
        plugin.startGame();
        sender.sendMessage("§a[DSV] §fเริ่มเกม Diamond Survival แล้ว!");
        return true;
    }

    // ─── /mbdsv stop ──────────────────────────────────────────────────
    public boolean stop(CommandSender sender) {
        if (!plugin.isGameRunning()) {
            sender.sendMessage("§c[DSV] เกมไม่ได้ทำงานอยู่!");
            return true;
        }
        plugin.stopGame();
        sender.sendMessage("§c[DSV] §fหยุดเกม Diamond Survival แล้ว!");
        return true;
    }

    // ─── /mbdsv add <amount> ──────────────────────────────────────────
    public boolean add(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv add <amount>");
            return true;
        }
        int amount;
        try { amount = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] ตัวเลขไม่ถูกต้อง: " + args[0]);
            return true;
        }
        if (amount <= 0) {
            sender.sendMessage("§c[DSV] จำนวนต้องมากกว่า 0");
            return true;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            dm.addDiamonds(player.getUniqueId(), amount);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);

            if (dm.getDiamonds(player.getUniqueId()) >= dm.getGoal()) {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }
        return true;
    }

    // ─── /mbdsv remove <amount> ───────────────────────────────────────
    public boolean remove(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv remove <amount>");
            return true;
        }
        int amount;
        try { amount = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] ตัวเลขไม่ถูกต้อง: " + args[0]);
            return true;
        }
        if (amount <= 0) {
            sender.sendMessage("§c[DSV] จำนวนต้องมากกว่า 0");
            return true;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            dm.removeDiamonds(player.getUniqueId(), amount);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        }
        return true;
    }

    // ─── /mbdsv reset ─────────────────────────────────────────────────
    public boolean reset(CommandSender sender) {
        plugin.stopCountdown();
        for (Player player : Bukkit.getOnlinePlayers()) {
            dm.setDiamonds(player.getUniqueId(), 0);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 0.7f);
        }
        return true;
    }

    // ─── /mbdsv wrap ──────────────────────────────────────────────────
    // Random teleport to a nearby location
    public boolean wrap(CommandSender sender) {
        int spread = plugin.getConfig().getInt("warp.spread", 500);
        int minSpread = plugin.getConfig().getInt("warp.min-spread", 100);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            Location current = player.getLocation();

            // Random offset with minimum distance
            int dx, dz;
            do {
                dx = rng.nextInt(-spread, spread + 1);
                dz = rng.nextInt(-spread, spread + 1);
            } while (Math.abs(dx) < minSpread && Math.abs(dz) < minSpread);

            int newX = current.getBlockX() + dx;
            int newZ = current.getBlockZ() + dz;

            // Load chunk and find safe Y
            world.getChunkAtAsync(newX >> 4, newZ >> 4).thenAccept(chunk -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int newY = world.getHighestBlockYAt(newX, newZ) + 1;
                    Location target = new Location(world, newX + 0.5, newY, newZ + 0.5,
                            player.getLocation().getYaw(), player.getLocation().getPitch());

                    player.teleport(target);
                    player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    player.playSound(target, Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.5f);
                    player.sendTitle("§d§lWarp!", "§fไปยังตำแหน่ง §e" + newX + " §f/ §e" + newZ, 5, 40, 10);

                    // Particles at arrival
                    world.spawnParticle(Particle.PORTAL, target, 50, 1, 1, 1, 0.5);
                    world.spawnParticle(Particle.END_ROD, target, 20, 0.5, 1, 0.5, 0.05);
                });
            });
        }
        return true;
    }

    // ─── /mbdsv scan [seconds] ────────────────────────────────────────
    // Highlight diamond ores with white glowing shulker outlines (async scan)
    public boolean scan(CommandSender sender, String[] args) {
        int duration = plugin.getConfig().getInt("scan.duration-seconds", 10);
        int radius = plugin.getConfig().getInt("scan.radius", 30);
        if (args.length >= 1) {
            try { duration = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        final int durationTicks = duration * 20;

        for (Player player : Bukkit.getOnlinePlayers()) {
            Location center = player.getLocation();
            World world = center.getWorld();

            int cx = center.getBlockX();
            int cy = center.getBlockY();
            int cz = center.getBlockZ();
            int yMin = Math.max(world.getMinHeight(), cy - radius);
            int yMax = Math.min(world.getMaxHeight() - 1, cy + radius);

            player.sendTitle("§7§lกำลังสแกน...", "§7รัศมี " + radius + " บล็อค", 5, 40, 10);

            // Async block scanning to prevent lag
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<Location> diamondLocations = new ArrayList<>();
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        for (int y = yMin; y <= yMax; y++) {
                            Block block = world.getBlockAt(cx + x, y, cz + z);
                            Material type = block.getType();
                            if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
                                diamondLocations.add(block.getLocation().add(0.5, 0, 0.5));
                            }
                        }
                    }
                }

                // Back to main thread for entity spawning & effects
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    if (diamondLocations.isEmpty()) {
                        player.sendTitle("§c§lไม่มีเพชรเบย ;-;", "§7ลองย้ายที่แล้วสแกนใหม่นะ", 5, 35, 10);
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                        world.spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.02);
                        return;
                    }

                    // Setup scoreboard team for white glow
                    Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
                    Team scanTeam = scoreboard.getTeam("dsv_scan");
                    if (scanTeam == null) {
                        scanTeam = scoreboard.registerNewTeam("dsv_scan");
                    }
                    scanTeam.color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
                    final Team team = scanTeam;

                    List<Shulker> markers = new ArrayList<>();
                    for (Location loc : diamondLocations) {
                        Shulker shulker = world.spawn(loc, Shulker.class, s -> {
                            s.setInvisible(true);
                            s.setInvulnerable(true);
                            s.setSilent(true);
                            s.setAI(false);
                            s.setGlowing(true);
                            s.setGravity(false);
                            s.setPersistent(false);
                            s.setCollidable(false);
                        });
                        team.addEntity(shulker);
                        markers.add(shulker);
                    }

                    // Diamonds found — exciting effects
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 2f);
                    player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.5f);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
                    player.sendTitle("§b§l✦ SCAN ✦", "§fพบ §b" + markers.size() + " §fเพชร!", 5, 40, 10);

                    Location pLoc = player.getLocation();
                    world.spawnParticle(Particle.END_ROD, pLoc.clone().add(0, 1, 0), 60, 2, 2, 2, 0.1);
                    world.spawnParticle(Particle.ENCHANT, pLoc, 100, 3, 3, 3, 1);

                    for (Shulker s : markers) {
                        Location sLoc = s.getLocation();
                        world.spawnParticle(Particle.END_ROD, sLoc.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.02);
                        world.spawnParticle(Particle.HAPPY_VILLAGER, sLoc, 5, 0.3, 0.3, 0.3);
                    }

                    // Periodic sparkle
                    final int[] sparkleTask = {-1};
                    sparkleTask[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                        for (Shulker s : markers) {
                            if (s.isValid()) {
                                world.spawnParticle(Particle.END_ROD, s.getLocation().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0.01);
                            }
                        }
                    }, 20L, 20L);

                    // Remove markers after duration
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        Bukkit.getScheduler().cancelTask(sparkleTask[0]);
                        for (Shulker s : markers) {
                            if (s.isValid()) s.remove();
                        }
                        if (player.isOnline()) {
                            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1f);
                        }
                    }, durationTicks);
                });
            });
        }
        return true;
    }

    // ─── /mbdsv time <seconds> ────────────────────────────────────────
    public boolean time(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv time <seconds>");
            return true;
        }
        int seconds;
        try { seconds = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] ตัวเลขไม่ถูกต้อง: " + args[0]);
            return true;
        }
        if (seconds <= 0) {
            sender.sendMessage("§c[DSV] จำนวนวินาทีต้องมากกว่า 0");
            return true;
        }
        plugin.getConfig().set("countdown-seconds", seconds);
        plugin.saveConfig();
        return true;
    }

    // ─── /mbdsv set <amount> ─────────────────────────────────────────
    public boolean setGoal(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv set <amount>");
            return true;
        }
        int goal;
        try { goal = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] ตัวเลขไม่ถูกต้อง: " + args[0]);
            return true;
        }
        if (goal <= 0) {
            sender.sendMessage("§c[DSV] จำนวนเป้าต้องมากกว่า 0");
            return true;
        }
        plugin.getConfig().set("goal", goal);
        plugin.saveConfig();
        return true;
    }

    // ─── /mbdsv death <on|off> ──────────────────────────────────────
    public boolean death(CommandSender sender, String[] args) {
        if (args.length < 1) {
            boolean current = plugin.isDeathDropItems();
            sender.sendMessage("§b[DSV] §fตายแล้วของ drop: " + (current ? "§aON" : "§cOFF"));
            return true;
        }
        String toggle = args[0].toLowerCase();
        if (toggle.equals("on")) {
            plugin.setDeathDropItems(true);
            sender.sendMessage("§a[DSV] §fเปิดระบบ drop ของตอนตาย");
        } else if (toggle.equals("off")) {
            plugin.setDeathDropItems(false);
            sender.sendMessage("§c[DSV] §fปิดระบบ drop ของตอนตาย (เก็บของไว้)");
        } else {
            sender.sendMessage("§c[DSV] Usage: /mbdsv death <on|off>");
        }
        return true;
    }

    // ─── /mbdsv mob <name> <count> ───────────────────────────────────
    public boolean mob(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[DSV] ต้องใช้ในเกมเท่านั้น");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv mob <name> <count>");
            return true;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c[DSV] ไม่รู้จัก Mob: " + args[0]);
            return true;
        }
        if (!type.isSpawnable()) {
            sender.sendMessage("§c[DSV] ไม่สามารถเสก Mob นี้ได้: " + args[0]);
            return true;
        }
        int count;
        try { count = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] จำนวนไม่ถูกต้อง: " + args[1]);
            return true;
        }
        if (count <= 0 || count > 100) {
            sender.sendMessage("§c[DSV] จำนวนต้องอยู่ระหว่าง 1-100");
            return true;
        }
        Location loc = player.getLocation();
        World world = loc.getWorld();
        for (int i = 0; i < count; i++) {
            double ox = (Math.random() - 0.5) * 6;
            double oz = (Math.random() - 0.5) * 6;
            world.spawnEntity(loc.clone().add(ox, 0, oz), type);
        }
        player.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.5f);
        return true;
    }

    // ─── /mbdsv tnt <count> ─────────────────────────────────────────
    public boolean tnt(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[DSV] ต้องใช้ในเกมเท่านั้น");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv tnt <count>");
            return true;
        }
        int count;
        try { count = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] จำนวนไม่ถูกต้อง: " + args[0]);
            return true;
        }
        if (count <= 0 || count > 50) {
            sender.sendMessage("§c[DSV] จำนวนต้องอยู่ระหว่าง 1-50");
            return true;
        }
        Location loc = player.getLocation();
        World world = loc.getWorld();
        for (int i = 0; i < count; i++) {
            double ox = (Math.random() - 0.5) * 8;
            double oz = (Math.random() - 0.5) * 8;
            TNTPrimed tntEntity = world.spawn(loc.clone().add(ox, 1, oz), TNTPrimed.class);
            tntEntity.setFuseTicks(20); // 1 second
        }
        player.playSound(loc, Sound.ENTITY_TNT_PRIMED, 1f, 1f);
        return true;
    }

    // ─── /mbdsv lava <count> ────────────────────────────────────────
    public boolean lava(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[DSV] ต้องใช้ในเกมเท่านั้น");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§c[DSV] Usage: /mbdsv lava <count>");
            return true;
        }
        int count;
        try { count = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            sender.sendMessage("§c[DSV] จำนวนไม่ถูกต้อง: " + args[0]);
            return true;
        }
        if (count <= 0 || count > 20) {
            sender.sendMessage("§c[DSV] จำนวนต้องอยู่ระหว่าง 1-20");
            return true;
        }
        Location loc = player.getLocation();
        World world = loc.getWorld();
        for (int i = 0; i < count; i++) {
            double ox = (Math.random() - 0.5) * 10;
            double oz = (Math.random() - 0.5) * 10;
            Location target = loc.clone().add(ox, 5, oz);
            target.setY(world.getHighestBlockYAt(target) + 1);
            target.getBlock().setType(Material.LAVA);
        }
        player.playSound(loc, Sound.ITEM_BUCKET_EMPTY_LAVA, 1f, 1f);
        return true;
    }
}
