package com.reaction.desktop;

/**
 * A connection to one peer, independent of mechanism (legacy server or WebRTC P2P). Same idea
 * as the Android Transport interface so the desktop CLI drives both modes uniformly.
 */
public interface Transport {
    enum State { IDLE, SIGNALING, CONNECTING, CONNECTED, FAILED, CLOSED }
    enum Route { UNKNOWN, DIRECT, RELAY }

    void setListener(Listener l);
    void start();
    void send(String text);
    void close();

    interface Listener {
        default void onState(State s) {}
        default void onStatus(String line) {}
        default void onMessage(long id, String from, String text) {}
        default void onError(String message) {}
        default void onRoute(Route route, String detail) {}
    }
}
