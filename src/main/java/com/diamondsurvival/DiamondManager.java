package com.diamondsurvival;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DiamondManager {

    private final DiamondSurvivalPlugin plugin;
    private final File dataFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Player UUID -> diamond count
    private final Map<String, Integer> diamonds = new ConcurrentHashMap<>();

    public DiamondManager(DiamondSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "diamonds.json");
    }

    public int getGoal() {
        return plugin.getConfig().getInt("goal", 1000);
    }

    public int getDiamonds(UUID playerId) {
        return diamonds.getOrDefault(playerId.toString(), 0);
    }

    public void setDiamonds(UUID playerId, int amount) {
        diamonds.put(playerId.toString(), amount);
        save();
    }

    public void addDiamonds(UUID playerId, int amount) {
        setDiamonds(playerId, getDiamonds(playerId) + amount);
    }

    public void removeDiamonds(UUID playerId, int amount) {
        setDiamonds(playerId, getDiamonds(playerId) - amount);
    }

    // ─── Sync physical diamonds to match counter (1 slot, max 64) ───
    public void syncInventory(Player player) {
        int counter = getDiamonds(player.getUniqueId());
        int target = Math.max(0, Math.min(counter, 64));

        PlayerInventory inv = player.getInventory();
        int firstSlot = -1;

        // Find first diamond slot, remove extras
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.DIAMOND) {
                if (firstSlot == -1) firstSlot = i;
                else inv.setItem(i, null);
            }
        }

        if (target > 0) {
            if (firstSlot == -1) {
                inv.addItem(new ItemStack(Material.DIAMOND, target));
            } else {
                ItemStack stack = inv.getItem(firstSlot);
                if (stack.getAmount() != target) stack.setAmount(target);
            }
        } else if (firstSlot != -1) {
            inv.setItem(firstSlot, null);
        }
    }

    public void load() {
        if (!dataFile.exists()) return;
        try (FileReader reader = new FileReader(dataFile)) {
            Type mapType = new TypeToken<Map<String, Integer>>(){}.getType();
            Map<String, Integer> loaded = gson.fromJson(reader, mapType);
            if (loaded != null) {
                diamonds.clear();
                diamonds.putAll(loaded);
                plugin.getLogger().info("Loaded " + diamonds.size() + " player diamond records");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load diamonds: " + e.getMessage());
        }
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            try (FileWriter writer = new FileWriter(dataFile)) {
                gson.toJson(diamonds, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save diamonds: " + e.getMessage());
        }
    }
}
