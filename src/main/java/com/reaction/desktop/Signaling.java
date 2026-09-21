package com.reaction.desktop;

import java.net.URI;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/**
 * WebSocket signaling client (Java-WebSocket). Connects to wss://host/ws?room=..., sends `join`,
 * relays `signal` frames. Does not reconnect itself; P2pClient decides when signaling is needed.
 */
public class Signaling {
    public interface Callbacks {
        void onSignalMessage(JSONObject msg);
        void onSignalOpen();
        void onSignalClosed(int code, String reason, boolean byUs);
        void onSignalError(String message);
    }

    private final String room;
    private final String token;
    private final String peerId;
    private final Callbacks cb;
    private WebSocketClient ws;
    private volatile boolean joined;
    private volatile boolean closedByUs;

    public Signaling(String wsUrl, String room, String token, String peerId, Callbacks cb) {
        this.room = room;
        this.token = token;
        this.peerId = peerId;
        this.cb = cb;
        String sep = wsUrl.contains("?") ? "&" : "?";
        URI uri = URI.create(wsUrl + sep + "room=" + room);
        this.ws = new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake h) {
                Signaling.this.send(new JSONObject().put("type", "join").put("room", Signaling.this.room)
                    .put("token", Signaling.this.token).put("peerId", Signaling.this.peerId));
                cb.onSignalOpen();
            }
            @Override public void onMessage(String message) {
                JSONObject msg;
                try { msg = new JSONObject(message); } catch (Exception e) { return; }
                if ("joined".equals(msg.optString("type"))) joined = true;
                cb.onSignalMessage(msg);
            }
            @Override public void onClose(int code, String reason, boolean remote) {
                joined = false;
                cb.onSignalClosed(code, reason, closedByUs);
            }
            @Override public void onError(Exception ex) {
                if (!closedByUs) cb.onSignalError(ex.getMessage());
            }
        };
    }

    public void connect() {
        closedByUs = false;
        ws.connect();
    }

    public boolean isJoined() { return joined; }

    public boolean send(JSONObject obj) {
        obj.put("v", 1);
        if (ws != null && ws.isOpen()) { ws.send(obj.toString()); return true; }
        return false;
    }

    public boolean signal(String kind, int epoch, JSONObject data) {
        return send(new JSONObject().put("type", "signal").put("kind", kind).put("epoch", epoch).put("data", data));
    }

    public void close() {
        closedByUs = true;
        if (ws != null) ws.close();
    }
}
