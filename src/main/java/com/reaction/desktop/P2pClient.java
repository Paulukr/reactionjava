package com.reaction.desktop;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * WebRTC DataChannel transport for the desktop (webrtc-java), mirroring reactionjs/web/js/
 * p2p-transport.js and the shared PROTOCOL.md. Signaling is used only for setup/renegotiation;
 * application messages travel over the DataChannel. All WebRTC calls are serialized on one thread.
 */
public class P2pClient implements Transport, Signaling.Callbacks {
    private static final int MAX_RETRIES = 5;
    private static PeerConnectionFactory sharedFactory;

    private final String wsUrl, room, token, peerId, clientName;
    private final List<RTCIceServer> ice;
    private final PeerConnectionFactory factory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2p-worker"); t.setDaemon(true); return t;
    });

    private Listener listener;
    private Signaling sig;
    private RTCPeerConnection pc;
    private RTCDataChannel dc;
    private String otherId, role;
    private int epoch;
    private boolean haveRemote;
    private final List<RTCIceCandidate> pending = new ArrayList<>();
    private int retries;
    private boolean manualClose;
    private long sendCounter;
    private State state = State.IDLE;

    public P2pClient(String wsUrl, String room, String token, String peerId, List<RTCIceServer> ice, String clientName) {
        this.wsUrl = wsUrl; this.room = room; this.token = token; this.peerId = peerId;
        this.clientName = clientName;
        this.ice = ice.isEmpty() ? defaultIce() : ice;
        this.factory = sharedFactory();
    }

    private static synchronized PeerConnectionFactory sharedFactory() {
        if (sharedFactory == null) sharedFactory = new PeerConnectionFactory();
        return sharedFactory;
    }

    private static List<RTCIceServer> defaultIce() {
        RTCIceServer s = new RTCIceServer();
        s.urls.add("stun:stun.cloudflare.com:3478");
        List<RTCIceServer> l = new ArrayList<>(); l.add(s); return l;
    }

    @Override public void setListener(Listener l) { this.listener = l; }

    @Override public void start() {
        worker.execute(() -> { manualClose = false; retries = 0; openSignaling(); });
    }

    private void openSignaling() {
        setState(State.SIGNALING);
        status("connecting to signaling");
        sig = new Signaling(wsUrl, room, token, peerId, this);
        sig.connect();
    }

    // ---- Signaling.Callbacks (hop onto the worker) ----

    @Override public void onSignalOpen() {}
    @Override public void onSignalError(String message) { worker.execute(() -> status("signaling error: " + message)); }

    @Override public void onSignalMessage(JSONObject msg) {
        worker.execute(() -> {
            switch (msg.optString("type")) {
                case "joined" -> {
                    var peers = msg.optJSONArray("peers");
                    status("joined room; " + (peers != null && peers.length() > 0 ? "peer present" : "waiting for peer"));
                    if (peers != null && peers.length() > 0) setPeer(peers.getString(0));
                }
                case "peer" -> {
                    if ("joined".equals(msg.optString("event"))) { status("peer joined"); setPeer(msg.optString("peerId")); }
                    else { status("peer left"); onPeerLeft(); }
                }
                case "signal" -> onPeerSignal(msg);
                case "error" -> error("signaling: " + msg.optString("code"));
                default -> {}
            }
        });
    }

    @Override public void onSignalClosed(int code, String reason, boolean byUs) {
        worker.execute(() -> {
            if (state == State.CONNECTED) { status("signaling closed (" + code + "); data channel still open"); return; }
            if (manualClose || byUs) return;
            status("signaling closed (" + code + "); reconnecting");
            scheduleRetry();
        });
    }

    // ---- negotiation ----

    private void setPeer(String id) {
        if (id.equals(otherId) && pc != null) return;
        otherId = id;
        role = Protocol.roleFor(peerId, id);
        status("role: " + role + " (peer " + id + ")");
        if (role.equals("offerer")) startOffer();
    }

    private void onPeerLeft() { teardownPeer(); otherId = null; setState(State.SIGNALING); }

    private void startOffer() {
        epoch += 1;
        final int ep = epoch;
        buildPeer(ep);
        RTCDataChannelInit init = new RTCDataChannelInit();
        init.ordered = true;
        dc = pc.createDataChannel(Protocol.DATACHANNEL_LABEL, init);
        wireChannel(dc);
        setState(State.CONNECTING);
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription desc) {
                pc.setLocalDescription(desc, new SetObs());
                sig.signal("offer", ep, new JSONObject().put("sdp", desc.sdp).put("type", "offer"));
            }
            @Override public void onFailure(String err) { error("offer failed: " + err); scheduleRetry(); }
        });
    }

    private void onPeerSignal(JSONObject msg) {
        String from = msg.optString("from");
        if (otherId != null && !from.isEmpty() && !from.equals(otherId)) return;
        if (otherId == null && !from.isEmpty()) setPeer(from);
        String kind = msg.optString("kind");
        int ep = msg.optInt("epoch", -1);
        JSONObject data = msg.optJSONObject("data");
        if (data == null) data = new JSONObject();

        if (kind.equals("bye")) { status("peer disconnected"); teardownPeer(); setState(State.SIGNALING); return; }

        if ("answerer".equals(role)) {
            if (kind.equals("offer")) {
                if (ep < epoch) return;
                epoch = ep;
                teardownPeer();
                buildPeer(ep);
                setState(State.CONNECTING);
                final JSONObject fdata = data;
                final int fep = ep;
                pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, fdata.optString("sdp")), new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() {
                        haveRemote = true;
                        flushCandidates();
                        pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                            @Override public void onSuccess(RTCSessionDescription desc) {
                                pc.setLocalDescription(desc, new SetObs());
                                sig.signal("answer", fep, new JSONObject().put("sdp", desc.sdp).put("type", "answer"));
                            }
                            @Override public void onFailure(String err) { error("answer failed: " + err); scheduleRetry(); }
                        });
                    }
                    @Override public void onFailure(String err) { error("setRemote(offer) failed: " + err); scheduleRetry(); }
                });
            } else if (kind.equals("candidate")) {
                addCandidate(ep, data);
            }
            return;
        }

        // offerer
        if (kind.equals("answer")) {
            if (ep != epoch || pc == null) return;
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, data.optString("sdp")), new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { haveRemote = true; flushCandidates(); }
                @Override public void onFailure(String err) { error("apply answer failed: " + err); scheduleRetry(); }
            });
        } else if (kind.equals("candidate")) {
            addCandidate(ep, data);
        }
    }

    private void addCandidate(int ep, JSONObject data) {
        if (ep != epoch) return;
        if (data.isNull("candidate")) return;
        RTCIceCandidate cand = new RTCIceCandidate(data.optString("sdpMid"), data.optInt("sdpMLineIndex"), data.optString("candidate"));
        if (pc == null || !haveRemote) pending.add(cand); else pc.addIceCandidate(cand);
    }

    private void flushCandidates() {
        for (RTCIceCandidate c : pending) pc.addIceCandidate(c);
        pending.clear();
    }

    // ---- peer connection wiring ----

    private void buildPeer(int ep) {
        haveRemote = false;
        pending.clear();
        RTCConfiguration cfg = new RTCConfiguration();
        cfg.iceServers.addAll(ice);
        pc = factory.createPeerConnection(cfg, new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate candidate) {
                JSONObject data = new JSONObject().put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex);
                worker.execute(() -> { if (sig != null) sig.signal("candidate", ep, data); });
            }
            @Override public void onDataChannel(RTCDataChannel channel) {
                worker.execute(() -> { dc = channel; wireChannel(channel); });
            }
            @Override public void onConnectionChange(RTCPeerConnectionState s) {
                worker.execute(() -> {
                    status("pc: " + s);
                    if (s == RTCPeerConnectionState.FAILED) onIceFailed();
                });
            }
        });
    }

    private void wireChannel(RTCDataChannel channel) {
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long previousAmount) {}
            @Override public void onStateChange() { worker.execute(() -> handleChannelState(channel)); }
            @Override public void onMessage(RTCDataChannelBuffer buffer) {
                if (buffer.binary) return;
                ByteBuffer b = buffer.data;
                byte[] bytes = new byte[b.remaining()]; b.get(bytes);
                JSONObject frame = Protocol.parseAppFrame(new String(bytes, StandardCharsets.UTF_8));
                if (frame == null) return;
                if ("hello".equals(frame.optString("type"))) {
                    status("peer: " + frame.optString("name") + " (" + frame.optString("platform") + ")");
                } else if ("msg".equals(frame.optString("type")) && listener != null) {
                    listener.onMessage(frame.optLong("id"), frame.optString("from"), frame.optString("text"));
                }
            }
        });
        // The channel may already be OPEN by the time we registered (esp. the answerer's received
        // channel), so the onStateChange(OPEN) callback can be missed — handle the current state now.
        handleChannelState(channel);
    }

    private boolean opened;

    private void handleChannelState(RTCDataChannel channel) {
        RTCDataChannelState st = channel.getState();
        if (st == RTCDataChannelState.OPEN) {
            if (opened) return;
            opened = true;
            retries = 0;
            setState(State.CONNECTED);
            status("data channel open");
            rawSend(Protocol.helloMessage(clientName, "desktop").toString());
        } else if (st == RTCDataChannelState.CLOSED && state == State.CONNECTED && !manualClose) {
            opened = false;
            status("data channel closed"); setState(State.FAILED); scheduleRetry();
        }
    }

    private void onIceFailed() {
        if (state == State.CONNECTED) return;
        status("ICE failed"); setState(State.FAILED); scheduleRetry();
    }

    // ---- reconnection ----

    private void scheduleRetry() {
        if (manualClose) return;
        if (retries >= MAX_RETRIES) { error("giving up after retries"); setState(State.FAILED); return; }
        long delay = Math.min(16000L, 1000L << retries);
        retries += 1;
        status("retry " + retries + "/" + MAX_RETRIES + " in " + delay + " ms");
        worker.schedule(() -> {
            teardownPeer();
            if (sig == null || !sig.isJoined()) { if (sig != null) sig.close(); openSignaling(); }
            else if (otherId != null) setPeer(otherId);
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ---- app API ----

    @Override public void send(String text) {
        worker.execute(() -> {
            try { rawSend(Protocol.appMessage(++sendCounter, clientName, text).toString()); }
            catch (Exception e) { error("send: " + e.getMessage()); }
        });
    }

    private void rawSend(String frame) {
        if (dc == null || dc.getState() != RTCDataChannelState.OPEN) throw new IllegalStateException("not connected");
        if (frame.length() > Protocol.MAX_APP_FRAME) throw new IllegalStateException("message too large");
        ByteBuffer buf = ByteBuffer.wrap(frame.getBytes(StandardCharsets.UTF_8));
        try {
            dc.send(new RTCDataChannelBuffer(buf, false));
        } catch (Exception e) {
            throw new IllegalStateException("data channel send failed: " + e.getMessage(), e);
        }
    }

    @Override public void close() {
        worker.execute(() -> {
            manualClose = true;
            if (sig != null && sig.isJoined()) sig.signal("bye", epoch == 0 ? 1 : epoch, new JSONObject().put("reason", "user"));
            teardownPeer();
            if (sig != null) { sig.close(); sig = null; }
            setState(State.CLOSED);
        });
    }

    private void teardownPeer() {
        if (dc != null) { try { dc.close(); } catch (Exception ignored) {} dc = null; }
        if (pc != null) { try { pc.close(); } catch (Exception ignored) {} pc = null; }
        haveRemote = false;
        opened = false;
        pending.clear();
    }

    private void setState(State s) { state = s; if (listener != null) listener.onState(s); }
    private void status(String line) { if (listener != null) listener.onStatus(line); }
    private void error(String message) { if (listener != null) listener.onError(message); }

    /** No-op SetSessionDescriptionObserver for setLocalDescription. */
    private static final class SetObs implements SetSessionDescriptionObserver {
        @Override public void onSuccess() {}
        @Override public void onFailure(String error) {}
    }
}
