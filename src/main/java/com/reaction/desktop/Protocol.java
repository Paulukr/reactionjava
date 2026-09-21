package com.reaction.desktop;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Wire format shared with the browser (reactionjs) and Android (reaction) clients.
 * See reactionjs/PROTOCOL.md. Pure JVM; unit-tested by ProtocolTest.
 */
public final class Protocol {
    public static final int VERSION = 1;
    public static final String DATACHANNEL_LABEL = "reaction";
    public static final int MAX_APP_FRAME = 64 * 1024;

    private static final Pattern INVITE =
        Pattern.compile("^r1\\.([A-Za-z0-9_-]{22})\\.([A-Za-z0-9_-]{22})(?:@([A-Za-z0-9.\\-:]+))?$");
    private static final SecureRandom RNG = new SecureRandom();

    private Protocol() {}

    public record Invite(String room, String token, String host) {}

    public static Invite parseInvite(String code) {
        if (code == null) return null;
        Matcher m = INVITE.matcher(code.trim());
        if (!m.matches()) return null;
        return new Invite(m.group(1), m.group(2), m.group(3));
    }

    public static String formatInvite(String room, String token, String host) {
        return "r1." + room + "." + token + (host != null ? "@" + host : "");
    }

    /** Deterministic role: the lexicographically smaller peer id offers. */
    public static String roleFor(String myId, String otherId) {
        if (otherId == null) return "offerer";
        return myId.compareTo(otherId) < 0 ? "offerer" : "answerer";
    }

    public static String randomId(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String randomPeerId() {
        return randomId(9);
    }

    public static JSONObject appMessage(long id, String from, String text) {
        return new JSONObject().put("v", VERSION).put("type", "msg").put("id", id)
            .put("from", from).put("time", Instant.now().toString()).put("text", text);
    }

    public static JSONObject helloMessage(String name, String platform) {
        return new JSONObject().put("v", VERSION).put("type", "hello").put("name", name).put("platform", platform);
    }

    /** Validate a decoded application frame; null if invalid. */
    public static JSONObject parseAppFrame(String raw) {
        if (raw == null || raw.length() > MAX_APP_FRAME) return null;
        JSONObject obj;
        try { obj = new JSONObject(raw); } catch (Exception e) { return null; }
        if (obj.optInt("v", -1) != VERSION) return null;
        String type = obj.optString("type", "");
        if (type.isEmpty()) return null;
        if (type.equals("msg") && !obj.has("text")) return null;
        return obj;
    }
}
