package com.reaction.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import dev.onvoid.webrtc.RTCIceServer;
import org.json.JSONObject;
import org.junit.Test;

/**
 * End-to-end P2P over a REAL WebRTC DataChannel and a live signaling Worker. Two P2pClients run in
 * this JVM: one creates the room (offerer/answerer decided by peer id), the other joins; the test
 * asserts both DataChannels open and a message crosses each way.
 *
 * Requires a running signaling service. It is SKIPPED unless REACTION_SIGNALING is set, e.g.:
 *   cd signaling && npx wrangler dev --port 8796 &   # from a standalone copy (see README)
 *   REACTION_SIGNALING=http://127.0.0.1:8796 ./gradlew test --tests '*P2pLoopbackTest'
 */
public class P2pLoopbackTest {

    @Test(timeout = 60000)
    public void twoPeersExchangeOverDataChannel() throws Exception {
        String signaling = System.getenv("REACTION_SIGNALING");
        assumeTrue("set REACTION_SIGNALING to run the live P2P test", signaling != null && !signaling.isBlank());
        signaling = signaling.replaceAll("/+$", "");

        // Create a room via the Worker.
        HttpResponse<String> res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(signaling + "/rooms"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode());
        JSONObject room = new JSONObject(res.body());
        String ws = wsUrl(signaling);
        List<RTCIceServer> ice = stun();

        CountDownLatch aOpen = new CountDownLatch(1), bOpen = new CountDownLatch(1);
        List<String> aGot = new CopyOnWriteArrayList<>(), bGot = new CopyOnWriteArrayList<>();
        CountDownLatch aRecv = new CountDownLatch(1), bRecv = new CountDownLatch(1);

        P2pClient a = new P2pClient(ws, room.getString("room"), room.getString("token"), "peerA-aaaa", ice, "peerA");
        P2pClient b = new P2pClient(ws, room.getString("room"), room.getString("token"), "peerB-bbbb", ice, "peerB");
        a.setListener(new Transport.Listener() {
            @Override public void onState(Transport.State s) { System.err.println("[A] state " + s); if (s == Transport.State.CONNECTED) aOpen.countDown(); }
            @Override public void onStatus(String line) { System.err.println("[A] " + line); }
            @Override public void onError(String m) { System.err.println("[A] error " + m); }
            @Override public void onMessage(long id, String from, String text) { aGot.add(text); aRecv.countDown(); }
        });
        b.setListener(new Transport.Listener() {
            @Override public void onState(Transport.State s) { System.err.println("[B] state " + s); if (s == Transport.State.CONNECTED) bOpen.countDown(); }
            @Override public void onStatus(String line) { System.err.println("[B] " + line); }
            @Override public void onError(String m) { System.err.println("[B] error " + m); }
            @Override public void onMessage(long id, String from, String text) { bGot.add(text); bRecv.countDown(); }
        });

        try {
            a.start();
            b.start();
            assertTrue("peer A data channel did not open", aOpen.await(40, TimeUnit.SECONDS));
            assertTrue("peer B data channel did not open", bOpen.await(40, TimeUnit.SECONDS));

            b.send("hello-from-B");
            a.send("hello-from-A");
            assertTrue("A did not receive B's message", aRecv.await(10, TimeUnit.SECONDS));
            assertTrue("B did not receive A's message", bRecv.await(10, TimeUnit.SECONDS));
            assertEquals("hello-from-B", aGot.get(0));
            assertEquals("hello-from-A", bGot.get(0));
        } finally {
            a.close();
            b.close();
        }
    }

    private static List<RTCIceServer> stun() {
        RTCIceServer s = new RTCIceServer();
        s.urls.add("stun:stun.cloudflare.com:3478");
        List<RTCIceServer> l = new ArrayList<>();
        l.add(s);
        return l;
    }

    private static String wsUrl(String signaling) {
        URI u = URI.create(signaling);
        String scheme = "http".equals(u.getScheme()) ? "ws" : "wss";
        String port = u.getPort() == -1 ? "" : ":" + u.getPort();
        return scheme + "://" + u.getHost() + port + "/ws";
    }
}
