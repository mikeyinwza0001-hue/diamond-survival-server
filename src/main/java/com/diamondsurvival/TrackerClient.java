package com.diamondsurvival;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardened tracker client (v2 — HMAC signed, fail-closed).
 */
public class TrackerClient {

    private static final String PLUGIN_NAME = "DiamondSurvival";

    private final DiamondSurvivalPlugin plugin;
    private final String machineUuid;
    private final HttpClient httpClient;
    private final String jarHash;

    private volatile String accessStatus = "unknown";
    private BukkitTask heartbeatTask;

    public TrackerClient(DiamondSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.machineUuid = loadOrCreateUuid();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.jarHash = SecurityUtils.selfJarSha256();
        plugin.getLogger().info("[Tracker] Machine UUID: " + machineUuid);
        plugin.getLogger().info("[Tracker] Integrity hash: " + jarHash);
    }

    public String getAccessStatus() { return accessStatus; }
    public String getMachineUuid() { return machineUuid; }
    public boolean isApproved() { return "approved".equals(accessStatus); }

    private String loadOrCreateUuid() {
        File idFile = new File(plugin.getDataFolder(), "machine-id");
        if (idFile.exists()) {
            try {
                String stored = Files.readString(idFile.toPath()).trim();
                if (!stored.isEmpty()) {
                    plugin.getLogger().info("[Tracker] Loaded existing machine UUID: " + stored);
                    return stored;
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[Tracker] Failed to read machine-id: " + e.getMessage());
            }
        }
        String newUuid = UUID.randomUUID().toString();
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(idFile.toPath(), newUuid);
            plugin.getLogger().info("[Tracker] Generated new machine UUID: " + newUuid);
        } catch (IOException e) {
            plugin.getLogger().warning("[Tracker] Failed to save machine-id: " + e.getMessage());
        }
        return newUuid;
    }

    public void pingAsync(String event) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String hostname;
                try { hostname = java.net.InetAddress.getLocalHost().getHostName(); }
                catch (Exception e) { hostname = Bukkit.getServer().getName(); }

                StringBuilder playersListJson = new StringBuilder("[");
                Object[] onlinePlayers = Bukkit.getOnlinePlayers().toArray();
                for (int i = 0; i < onlinePlayers.length; i++) {
                    Player p = (Player) onlinePlayers[i];
                    playersListJson.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\"}",
                            escape(p.getName()), p.getUniqueId()));
                    if (i < onlinePlayers.length - 1) playersListJson.append(",");
                }
                playersListJson.append("]");

                String json = "{" +
                        "\"uuid\":\"" + machineUuid + "\"," +
                        "\"event\":\"" + event + "\"," +
                        "\"server\":\"" + escape(hostname) + "\"," +
                        "\"mc_version\":\"" + Bukkit.getMinecraftVersion() + "\"," +
                        "\"plugin_version\":\"" + plugin.getPluginMeta().getVersion() + "\"," +
                        "\"plugin_name\":\"" + PLUGIN_NAME + "\"," +
                        "\"jar_hash\":\"" + jarHash + "\"," +
                        "\"players\":" + onlinePlayers.length + "," +
                        "\"players_list\":" + playersListJson + "," +
                        "\"timestamp\":\"" + Instant.now() + "\"" +
                        "}";

                HttpResponse<String> resp = SecurityUtils.signedPost(httpClient, "/ping", json, 10);
                boolean sigOk = SecurityUtils.verifyResponse(resp);
                String body = resp.body();
                plugin.getLogger().info("[Tracker] Ping response (" + resp.statusCode()
                        + ", sig=" + (sigOk ? "ok" : "BAD") + "): " + body);

                if (!sigOk || resp.statusCode() != 200) {
                    if ("approved".equals(accessStatus)) {
                        plugin.getLogger().warning("[Tracker] Signature invalid — revoking approval");
                        accessStatus = "unknown";
                    }
                    return;
                }
                Matcher m = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (m.find()) {
                    String prev = accessStatus;
                    accessStatus = m.group(1);
                    if (!prev.equals(accessStatus)) {
                        plugin.getLogger().info("[Tracker] Access status changed: " + prev + " → " + accessStatus);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Tracker] Ping failed: " + e.getMessage());
            }
        });
    }

    public void startHeartbeat() {
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> pingAsync("heartbeat"), 6000L, 6000L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollCommands, 600L, 600L);
    }

    public void sendPlayerEvent(String event, Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String ip = "";
                try {
                    if (player.getAddress() != null) ip = player.getAddress().getAddress().getHostAddress();
                } catch (Exception ignored) {}

                String json = "{" +
                        "\"uuid\":\"" + machineUuid + "\"," +
                        "\"event\":\"" + event + "\"," +
                        "\"player_name\":\"" + escape(player.getName()) + "\"," +
                        "\"player_uuid\":\"" + player.getUniqueId() + "\"," +
                        "\"player_ip\":\"" + ip + "\"," +
                        "\"timestamp\":\"" + Instant.now() + "\"" +
                        "}";
                SecurityUtils.signedPost(httpClient, "/player-event", json, 10);
            } catch (Exception e) {
                plugin.getLogger().warning("[Tracker] Player event failed: " + e.getMessage());
            }
        });
    }

    public void pollCommands() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpResponse<String> res = SecurityUtils.signedGet(httpClient,
                        "/commands/pending?uuid=" + machineUuid, 10);
                if (res.statusCode() != 200 || !SecurityUtils.verifyResponse(res)) return;
                List<Integer> ids = new ArrayList<>();
                List<String> cmds = new ArrayList<>();
                Matcher m = Pattern.compile(
                        "\"id\"\\s*:\\s*(\\d+).*?\"command\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                        Pattern.DOTALL).matcher(res.body());
                while (m.find()) { ids.add(Integer.parseInt(m.group(1))); cmds.add(m.group(2)); }
                if (ids.isEmpty()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String c : cmds) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            SecurityUtils.signedPost(httpClient, "/commands/ack",
                                    "{\"ids\":" + ids.toString().replace(" ", "") + "}", 10);
                        } catch (Exception ignored) {}
                    });
                });
            } catch (Exception ignored) {}
        });
    }

    public void stop() {
        if (heartbeatTask != null) heartbeatTask.cancel();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
