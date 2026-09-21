package com.reaction.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.onvoid.webrtc.RTCIceServer;
import org.json.JSONObject;

/**
 * One-shot end-to-end check of a deployed (or local) Reaction Worker, from a single JVM:
 *   1. GET /health                              -> 200 "ok"
 *   2. GET /  without login                     -> 401 (the site gate is closed)
 *   3. GET /  with the login                    -> 200 (gate opens, cookie set)   [needs --auth]
 *   4. POST /rooms with the login               -> 201 (authenticated room creation)
 *   5. two real WebRTC peers (P2pClient) join the room via signaling + STUN, the DataChannel
 *      opens on both sides and a message crosses each way.
 * Used by `reactionjava selftest` and by P2pLoopbackTest. Prints one line per step.
 */
public final class SelfTest {
    public record Result(boolean passed, List<String> lines) {}

    private final String signalingUrl;   // https://host or http://host:port, no trailing slash
    private final String auth;           // "login:password" or null
    private final int timeoutSec;
    private final List<String> lines = new ArrayList<>();
    private boolean ok = true;

    public SelfTest(String signalingUrl, String auth, int timeoutSec) {
        this.signalingUrl = signalingUrl.replaceAll("/+$", "");
        this.auth = (auth == null || auth.isBlank()) ? null : auth;
        this.timeoutSec = timeoutSec;
    }

    public Result run() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            HttpResponse<String> h = get(http, "/health", null);
            step("GET /health", h.statusCode() == 200 && "ok".equals(h.body().trim()), "HTTP " + h.statusCode());

            HttpResponse<String> gateClosed = get(http, "/", null);
            step("GET / without login -> 401 (gate closed)", gateClosed.statusCode() == 401, "HTTP " + gateClosed.statusCode());

            if (auth != null) {
                HttpResponse<String> gateOpen = get(http, "/", auth);
                boolean cookie = gateOpen.headers().firstValue("set-cookie").map(c -> c.startsWith("reaction_auth=")).orElse(false);
                step("GET / with login -> 200 + cookie (gate opens)", gateOpen.statusCode() == 200 && cookie, "HTTP " + gateOpen.statusCode());
            } else {
                lines.add("skip  GET / with login (no --auth given)");
            }

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(signalingUrl + "/rooms"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
            if (auth != null) b.header("Authorization", basic(auth));
            HttpResponse<String> created = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            step("POST /rooms" + (auth != null ? " with login" : "") + " -> 201", created.statusCode() == 201, "HTTP " + created.statusCode() + " " + created.body().substring(0, Math.min(80, created.body().length())));
            if (created.statusCode() != 201) return new Result(false, lines);
            JSONObject room = new JSONObject(created.body());

            ok &= p2p(room.getString("room"), room.getString("token"));
        } catch (Exception e) {
            lines.add("FAIL  exception: " + e);
            ok = false;
        }
        lines.add(ok ? "PASS" : "FAIL");
        return new Result(ok, lines);
    }

    private boolean p2p(String room, String token) throws Exception {
        String ws = wsUrl(signalingUrl);
        List<RTCIceServer> ice = new ArrayList<>();
        RTCIceServer s = new RTCIceServer(); s.urls.add("stun:stun.cloudflare.com:3478"); ice.add(s);

        CountDownLatch aOpen = new CountDownLatch(1), bOpen = new CountDownLatch(1);
        CountDownLatch aRecv = new CountDownLatch(1), bRecv = new CountDownLatch(1);
        List<String> aGot = new CopyOnWriteArrayList<>(), bGot = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        P2pClient a = new P2pClient(ws, room, token, "selftest-a-" + Protocol.randomId(3), ice, "selftestA");
        P2pClient b = new P2pClient(ws, room, token, "selftest-b-" + Protocol.randomId(3), ice, "selftestB");
        a.setListener(new Transport.Listener() {
            @Override public void onState(Transport.State st) { if (st == Transport.State.CONNECTED) aOpen.countDown(); }
            @Override public void onError(String m) { errors.add("A: " + m); }
            @Override public void onMessage(long id, String from, String text) { aGot.add(text); aRecv.countDown(); }
        });
        b.setListener(new Transport.Listener() {
            @Override public void onState(Transport.State st) { if (st == Transport.State.CONNECTED) bOpen.countDown(); }
            @Override public void onError(String m) { errors.add("B: " + m); }
            @Override public void onMessage(long id, String from, String text) { bGot.add(text); bRecv.countDown(); }
        });
        long t0 = System.currentTimeMillis();
        try {
            a.start(); b.start();
            boolean open = aOpen.await(timeoutSec, TimeUnit.SECONDS) && bOpen.await(timeoutSec, TimeUnit.SECONDS);
            step("WebRTC: two peers via signaling + STUN, DataChannel open both sides", open,
                (System.currentTimeMillis() - t0) + " ms" + (errors.isEmpty() ? "" : " " + errors));
            if (!open) return false;
            b.send("selftest-from-B"); a.send("selftest-from-A");
            boolean got = aRecv.await(10, TimeUnit.SECONDS) && bRecv.await(10, TimeUnit.SECONDS)
                && aGot.contains("selftest-from-B") && bGot.contains("selftest-from-A");
            step("WebRTC: application message delivered each way", got, (System.currentTimeMillis() - t0) + " ms");
            return got;
        } finally {
            a.close(); b.close();
        }
    }

    private HttpResponse<String> get(HttpClient http, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(signalingUrl + path)).timeout(Duration.ofSeconds(15)).GET();
        if (auth != null) b.header("Authorization", basic(auth));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String auth) {
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }

    private void step(String what, boolean pass, String detail) {
        lines.add((pass ? "ok    " : "FAIL  ") + what + (detail == null || detail.isBlank() ? "" : "  (" + detail + ")"));
        ok &= pass;
    }

    static String wsUrl(String signalingUrl) {
        URI u = URI.create(signalingUrl);
        String scheme = "http".equals(u.getScheme()) ? "ws" : "wss";
        String port = u.getPort() == -1 ? "" : ":" + u.getPort();
        return scheme + "://" + u.getHost() + port + "/ws";
    }
}
