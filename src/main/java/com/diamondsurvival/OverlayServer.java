package com.diamondsurvival;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OverlayServer {

    private final DiamondSurvivalPlugin plugin;
    private final int port;
    private HttpServer server;

    public OverlayServer(DiamondSurvivalPlugin plugin, int port) {
        this.plugin = plugin;
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // API endpoint for overlay polling
            server.createContext("/api/state", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

                DiamondManager dm = plugin.getDiamondManager();
                int total = 0;
                String topPlayer = "";
                int topCount = 0;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    int count = dm.getDiamonds(p.getUniqueId());
                    total += count;
                    if (count > topCount) {
                        topCount = count;
                        topPlayer = p.getName();
                    }
                }

                boolean running = plugin.isGameRunning();

                String json = String.format(
                    "{\"current\":%d,\"goal\":%d,\"topPlayer\":\"%s\",\"topCount\":%d,\"online\":%d,\"running\":%b}",
                    total, dm.getGoal(), topPlayer, topCount, Bukkit.getOnlinePlayers().size(), running
                );

                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            });

            // Font file endpoint
            server.createContext("/fonts/KOMIKAX_.ttf", exchange -> {
                File fontFile = new File(plugin.getDataFolder(), "KOMIKAX_.ttf");
                if (!fontFile.exists()) fontFile = new File(plugin.getDataFolder(), "KOMIKAX_.TTF");
                if (!fontFile.exists()) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.getResponseBody().close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "font/ttf");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                byte[] fontBytes = Files.readAllBytes(fontFile.toPath());
                exchange.sendResponseHeaders(200, fontBytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(fontBytes); }
            });

            // Overlay HTML page
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (!path.equals("/") && !path.equals("/overlay") && !path.equals("/overlay.html")) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.getResponseBody().close();
                    return;
                }

                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                byte[] html = getOverlayHtml().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, html.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(html);
                }
            });

            server.setExecutor(null);
            server.start();
            plugin.getLogger().info("Overlay server started on port " + port);
            plugin.getLogger().info("OBS/TikTok Studio: http://localhost:" + port + "/overlay.html");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start overlay server: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("Overlay server stopped");
        }
    }

    private String getOverlayHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Diamond Survival Overlay</title>
<style>
@font-face{font-family:'KOMIKAX';src:url('/fonts/KOMIKAX_.ttf') format('truetype')}
*{margin:0;padding:0;box-sizing:border-box}
body{background:transparent;overflow:hidden;display:flex;align-items:center;justify-content:center;height:100vh;width:100vw}
.wrap{position:relative;display:inline-block}
#c{font-family:'KOMIKAX','Comic Sans MS',cursive;font-size:72px;color:#fff;letter-spacing:3px;text-shadow:3px 3px 0 #000,-1px -1px 0 #000,1px -1px 0 #000,-1px 1px 0 #000,0 0 20px rgba(0,0,0,0.5);white-space:nowrap;display:inline-block}
#c.bounce{animation:bounce 0.45s ease-out}
#status{font-family:'KOMIKAX','Comic Sans MS',cursive;font-size:24px;text-align:center;margin-top:8px;text-shadow:2px 2px 0 #000}
@keyframes bounce{0%{transform:scale(1)}25%{transform:scale(1.18)}50%{transform:scale(0.95)}75%{transform:scale(1.05)}100%{transform:scale(1)}}
.pop{position:absolute;right:-100px;top:50%;font-family:'KOMIKAX','Comic Sans MS',cursive;font-size:40px;pointer-events:none;white-space:nowrap;opacity:0;animation:popUp 1.5s ease-out forwards}
.pop.plus{color:#4dd9ff;text-shadow:0 0 12px rgba(77,217,255,0.9),2px 2px 0 #000}
.pop.minus{color:#ff4444;text-shadow:0 0 12px rgba(255,68,68,0.9),2px 2px 0 #000}
@keyframes popUp{0%{opacity:1;transform:translateY(-50%) scale(0.8)}20%{opacity:1;transform:translateY(-80%) scale(1.2)}70%{opacity:0.8;transform:translateY(-140%) scale(1)}100%{opacity:0;transform:translateY(-180%) scale(0.7)}}
</style>
</head>
<body>
<div class="wrap" id="wrap">
  <div id="c">0/1000</div>
  <div id="status"></div>
</div>
<script>
var prev=null;
function getColor(n){
  if(n<0)return'#ff4444';
  if(n===0)return'#ffffff';
  return'#4dd9ff';
}
function showPop(diff){
  var el=document.createElement('div');
  el.className='pop '+(diff>0?'plus':'minus');
  el.textContent=(diff>0?'+':'')+diff;
  document.getElementById('wrap').appendChild(el);
  setTimeout(function(){el.remove()},1600);
}
function poll(){
  fetch('/api/state').then(function(r){return r.json()}).then(function(d){
    var cur=d.current;
    var color=getColor(cur);
    document.getElementById('c').innerHTML='<span style="color:'+color+'">'+cur+'</span><span style="color:#fff">/</span><span style="color:#fff;margin-left:3px">'+d.goal+'</span>';
    var statusEl=document.getElementById('status');
    if(d.running){
      var pct=d.goal>0?(cur*100/d.goal).toFixed(1):'0.0';
      statusEl.innerHTML='<span style="color:#4dff4d">RUNNING</span> <span style="color:#aaa">'+pct+'%</span>';
    }else{
      statusEl.innerHTML='<span style="color:#ff4444">STOPPED</span>';
    }
    if(prev!==null&&cur!==prev){showPop(cur-prev);var ce=document.getElementById('c');ce.classList.remove('bounce');void ce.offsetWidth;ce.classList.add('bounce');}
    prev=cur;
  }).catch(function(){});
}
setInterval(poll,500);
poll();
</script>
</body>
</html>
""";
    }
}
