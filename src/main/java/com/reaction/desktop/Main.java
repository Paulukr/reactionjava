package com.reaction.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import dev.onvoid.webrtc.RTCIceServer;
import org.json.JSONObject;

/**
 * Reaction desktop client CLI. Same two modes as the Android/browser apps:
 *   reactionjava server  <baseUrl> [sendChannel receiveChannel]   (legacy msgserver)
 *   reactionjava create  <signalingUrl> [--stun url] [--name n]   (P2P: make a room, print invite)
 *   reactionjava join    <invite>        [--stun url] [--name n]  (P2P: join with an invitation)
 * After connecting, lines typed on stdin are sent; received messages are printed. Ctrl-D to quit.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        // Force autoflush so status lines appear immediately even when stdout is redirected to a file.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        if (args.length < 2) { usage(); return; }
        String mode = args[0];
        String name = optionValue(args, "--name", "desktop");
        String stun = optionValue(args, "--stun", "stun:stun.cloudflare.com:3478");

        if (mode.equals("selftest")) {
            String url = args[1];
            String auth = optionValue(args, "--auth", System.getenv().getOrDefault("REACTION_AUTH", ""));
            SelfTest.Result r = new SelfTest(url, auth, Integer.parseInt(optionValue(args, "--timeout", "40"))).run();
            r.lines().forEach(System.out::println);
            System.exit(r.passed() ? 0 : 1);
        }

        Transport transport;
        switch (mode) {
            case "server" -> {
                String baseUrl = args[1];
                String send = args.length > 2 && !args[2].startsWith("--") ? args[2] : "from-app";
                String recv = args.length > 3 && !args[3].startsWith("--") ? args[3] : "to-app";
                transport = new ServerClient(baseUrl, send, recv, name, 2000);
            }
            case "create" -> {
                String signalingUrl = args[1].replaceAll("/+$", "");
                String auth = optionValue(args, "--auth", System.getenv().getOrDefault("REACTION_AUTH", ""));
                JSONObject room = createRoom(signalingUrl, auth);
                String host = room.optString("host", URI.create(signalingUrl).getHost());
                System.out.println("invitation : " + room.getString("invite"));
                System.out.println("web link   : (append to your Pages URL) #invite=" + room.getString("invite"));
                transport = new P2pClient(wsUrl(signalingUrl), room.getString("room"), room.getString("token"),
                    Protocol.randomPeerId(), iceServers(stun), name);
            }
            case "join" -> {
                Protocol.Invite inv = Protocol.parseInvite(args[1]);
                if (inv == null) { System.err.println("invalid invitation"); return; }
                String ws = inv.host() != null ? wsScheme(inv.host()) + "://" + inv.host() + "/ws"
                    : wsUrl(optionValue(args, "--signaling", defaultSignalingUrl()));
                transport = new P2pClient(ws, inv.room(), inv.token(), Protocol.randomPeerId(), iceServers(stun), name);
            }
            default -> { usage(); return; }
        }

        transport.setListener(new Transport.Listener() {
            @Override public void onState(Transport.State s) { System.out.println("[state] " + s); }
            @Override public void onStatus(String line) { System.out.println("[info]  " + line); }
            @Override public void onError(String m) { System.out.println("[error] " + m); }
            @Override public void onRoute(Transport.Route r, String d) { System.out.println("[route] " + r + (d != null ? " (" + d + ")" : "")); }
            @Override public void onMessage(long id, String from, String text) { System.out.println("<< #" + id + " " + from + ": " + text); }
        });
        transport.start();

        Runtime.getRuntime().addShutdownHook(new Thread(transport::close));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) continue;
            transport.send(line);
            System.out.println(">> " + line);
        }
        transport.close();
        // The WebRTC native threads and the WebSocket client are not daemon threads: without an
        // explicit exit the JVM lingers after close(). Give close() a moment to send `bye`, then leave.
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        System.exit(0);
    }

    /** POST /rooms; the site login (`--auth login:password` or env REACTION_AUTH) is sent as Basic auth. */
    private static JSONObject createRoom(String signalingUrl, String auth) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(signalingUrl + "/rooms"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (auth != null && !auth.isBlank()) {
            b.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) throw new RuntimeException("create room: the signaling service wants the site login: --auth login:password (or REACTION_AUTH)");
        if (res.statusCode() == 429) throw new RuntimeException("create room: too many rooms from this address, try later");
        if (res.statusCode() != 201) throw new RuntimeException("create room failed: HTTP " + res.statusCode() + " " + res.body());
        return new JSONObject(res.body());
    }

    private static List<RTCIceServer> iceServers(String stun) {
        List<RTCIceServer> list = new ArrayList<>();
        if (stun != null && !stun.isBlank()) {
            RTCIceServer s = new RTCIceServer();
            s.urls.add(stun);
            list.add(s);
        }
        return list;
    }

    /** Loopback / private-range hosts use ws:// (no TLS); public hosts use wss://. */
    private static String wsScheme(String host) {
        String h = host.split(":")[0].toLowerCase();
        if (h.equals("localhost") || h.endsWith(".local")) return "ws";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.\\d+\\.\\d+$").matcher(h);
        if (!m.matches()) return "wss";
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        boolean local = a == 127 || a == 10 || (a == 192 && b == 168)
            || (a == 172 && b >= 16 && b <= 31) || (a == 100 && b >= 64 && b <= 127); // RFC 1918 + RFC 6598 (CGNAT)
        return local ? "ws" : "wss";
    }

    private static String wsUrl(String signalingUrl) {
        URI u = URI.create(signalingUrl);
        String scheme = "http".equals(u.getScheme()) ? "ws" : "wss";
        String port = u.getPort() == -1 ? "" : ":" + u.getPort();
        return scheme + "://" + u.getHost() + port + "/ws";
    }

    /** Default signaling URL from the bundled reaction.properties (synced from reactionjs/deploy.properties). */
    private static String defaultSignalingUrl() {
        try (var in = Main.class.getResourceAsStream("/reaction.properties")) {
            var p = new java.util.Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("signalingUrl", "").trim();
            if (!v.isEmpty()) return v;
        } catch (Exception ignored) { /* fall through */ }
        System.err.println("no signalingUrl in reaction.properties; pass --signaling <url>");
        return "https://reaction-signaling.example.workers.dev";
    }

    private static String optionValue(String[] args, String flag, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(flag)) return args[i + 1];
        return def;
    }

    private static void usage() {
        System.out.println("""
            Reaction desktop client
              reactionjava server   <baseUrl> [sendChannel receiveChannel]
              reactionjava create   <signalingUrl> [--auth login:pw] [--stun <url>] [--name <n>]
              reactionjava join     <invitation>   [--stun <url>] [--name <n>] [--signaling <url>]
              reactionjava selftest <signalingUrl> [--auth login:pw] [--timeout 40]
                  one-shot end-to-end check of a deployed Worker: health, gate, room, real WebRTC
            --auth defaults to $REACTION_AUTH. Type lines to send; Ctrl-D to quit.""");
    }
}
