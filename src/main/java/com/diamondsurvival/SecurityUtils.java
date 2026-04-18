package com.diamondsurvival;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Security primitives shared by TrackerClient and integrity checks.
 * — HMAC-SHA256 signing / verification (request & response)
 * — SHA-256 file hashing for self-integrity verification
 * — Runtime string reconstruction (avoids plaintext strings in the .class constant pool)
 *
 * Design notes
 * ------------
 *  • Strings like the tracker URL and shared secret are rebuilt from a char[] literal inside
 *    a static initializer. char[] literals are compiled as a sequence of bipush/sipush +
 *    castore bytecodes, so the full string never appears in the JAR's UTF-8 constant pool.
 *    Running `strings plugin.jar` will NOT find "mabeltracker" or the HMAC secret.
 *    It still can be recovered by decompiling, but raises the bar versus config.yml plain text.
 *  • The HMAC secret MUST match the HMAC_SECRET env var set on the tracker server (Railway).
 *    If you rotate it, rebuild the jar AND update Railway env at the same time.
 */
public final class SecurityUtils {

    /** Production tracker base URL — reconstructed at class load, not present as a string literal. */
    static final String TRACKER_URL;
    /** Shared HMAC-SHA256 secret — must equal HMAC_SECRET on the tracker server. */
    static final byte[] HMAC_SECRET;

    static {
        // "https://mabeltracker.up.railway.app"
        TRACKER_URL = new String(new char[] {
            'h','t','t','p','s',':','/','/',
            'm','a','b','e','l','t','r','a','c','k','e','r',
            '.','u','p','.','r','a','i','l','w','a','y','.','a','p','p'
        });

        // Production shared secret (rotated on 2026-04-18). MUST equal HMAC_SECRET env on Railway.
        // Plaintext value: "f72004c173e2ee4e794e1a3c6228ba731ea5f3753ac80eb7007b488a60ad6ace"
        HMAC_SECRET = new String(new char[] {
            'f','7','2','0','0','4','c','1','7','3','e','2','e','e','4','e',
            '7','9','4','e','1','a','3','c','6','2','2','8','b','a','7','3',
            '1','e','a','5','f','3','7','5','3','a','c','8','0','e','b','7',
            '0','0','7','b','4','8','8','a','6','0','a','d','6','a','c','e'
        }).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private SecurityUtils() {}

    /** Compute lowercase hex HMAC-SHA256 of the given message bytes. */
    public static String hmacHex(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SECRET, "HmacSHA256"));
            byte[] out = mac.doFinal(message);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC failed", e);
        }
    }

    /** Build the canonical signing string: "<timestampMs>\n<body>" */
    public static String canonical(long tsMs, String body) {
        return tsMs + "\n" + (body == null ? "" : body);
    }

    /** Constant-time string comparison to prevent timing attacks on signature verification. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // ─── High-level signed HTTP helpers ─────────────────────────────────────
    /** Allowed clock skew (5 min) when verifying response timestamps. */
    public static final long MAX_CLOCK_SKEW_MS = 5L * 60 * 1000;

    /** Signed POST to TRACKER_URL + path. Adds X-Timestamp + X-Signature headers. */
    public static HttpResponse<String> signedPost(HttpClient client, String path,
                                                  String jsonBody, int timeoutSec) throws Exception {
        long ts = System.currentTimeMillis();
        String sig = hmacHex(canonical(ts, jsonBody).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TRACKER_URL + path))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .header("X-Timestamp", Long.toString(ts))
                .header("X-Signature", sig)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Signed GET to TRACKER_URL + path (path may include query). Signature over path. */
    public static HttpResponse<String> signedGet(HttpClient client, String path, int timeoutSec) throws Exception {
        long ts = System.currentTimeMillis();
        String sig = hmacHex(canonical(ts, path).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TRACKER_URL + path))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("X-Timestamp", Long.toString(ts))
                .header("X-Signature", sig)
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Verify X-Signature/X-Timestamp on a response against its body. Returns true iff:
     *  • both headers present
     *  • timestamp within MAX_CLOCK_SKEW_MS
     *  • HMAC of "<ts>\n<body>" matches X-Signature (constant-time)
     */
    public static boolean verifyResponse(HttpResponse<String> res) {
        try {
            String sig = res.headers().firstValue("X-Signature").orElse(null);
            String tsStr = res.headers().firstValue("X-Timestamp").orElse(null);
            if (sig == null || tsStr == null) return false;
            long ts = Long.parseLong(tsStr);
            if (Math.abs(System.currentTimeMillis() - ts) > MAX_CLOCK_SKEW_MS) return false;
            String expect = hmacHex(canonical(ts, res.body()).getBytes(StandardCharsets.UTF_8));
            return constantTimeEquals(expect, sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** SHA-256 hex of the currently running plugin jar; returns "unknown" on failure. */
    public static String selfJarSha256() {
        try {
            File jar = new File(SecurityUtils.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!jar.isFile()) return "unknown";
            byte[] data = Files.readAllBytes(jar.toPath());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
