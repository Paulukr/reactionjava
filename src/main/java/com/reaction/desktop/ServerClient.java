package com.reaction.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Legacy transport: the msgserver HTTP protocol (see msgserver/README.md), the same one the
 * Android app and the desktop Java server speak. Sends plain text on sendChannel and polls
 * receiveChannel. This lets the desktop client talk to the existing server unchanged.
 */
public class ServerClient implements Transport {
    private final String baseUrl;
    private final String sendChannel;
    private final String receiveChannel;
    private final String clientName;
    private final long pollIntervalMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile boolean polling;
    private Thread pollThread;
    private Listener listener;

    public ServerClient(String baseUrl, String sendChannel, String receiveChannel, String clientName, long pollIntervalMs) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.sendChannel = sendChannel;
        this.receiveChannel = receiveChannel;
        this.clientName = clientName;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override public void setListener(Listener l) { this.listener = l; }

    @Override public void start() {
        polling = true;
        emitState(State.CONNECTING);
        pollThread = new Thread(this::pollLoop, "server-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        status("server mode: " + baseUrl);
    }

    private void pollLoop() {
        while (polling) {
            try {
                while (polling) {
                    HttpResponse<String> res = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/receive/" + receiveChannel))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 204) break;
                    if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode());
                    long id = parseLong(res.headers().firstValue("X-Msg-Id").orElse("-1"));
                    String from = res.headers().firstValue("X-From").orElse("?");
                    emitMessage(id, from, res.body());
                }
                emitState(State.CONNECTED);
            } catch (Exception e) {
                emitState(State.FAILED);
                error("poll: " + e.getMessage());
            }
            sleep(pollIntervalMs);
        }
    }

    @Override public void send(String text) {
        try {
            HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/send/" + sendChannel))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("X-From", clientName)
                    .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 201) throw new RuntimeException("HTTP " + res.statusCode());
        } catch (Exception e) {
            error("send: " + e.getMessage());
        }
    }

    @Override public void close() {
        polling = false;
        if (pollThread != null) pollThread.interrupt();
        emitState(State.CLOSED);
    }

    private void emitState(State s) { if (listener != null) listener.onState(s); }
    private void emitMessage(long id, String from, String text) { if (listener != null) listener.onMessage(id, from, text); }
    private void status(String s) { if (listener != null) listener.onStatus(s); }
    private void error(String s) { if (listener != null) listener.onError(s); }
    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (Exception e) { return -1; } }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
