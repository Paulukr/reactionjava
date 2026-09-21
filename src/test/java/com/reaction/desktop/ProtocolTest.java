package com.reaction.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Wire-format compatibility: these expectations mirror reactionjs/web/js/protocol.js and
 * reaction's P2pProtocolTest.kt so an invitation, role and message shape are identical across
 * the browser, Android and desktop clients.
 */
public class ProtocolTest {

    @Test public void invitationRoundTrips() {
        String room = "a".repeat(22), token = "b".repeat(22);
        String code = Protocol.formatInvite(room, token, "sig.example.com");
        assertEquals("r1." + room + "." + token + "@sig.example.com", code);
        Protocol.Invite p = Protocol.parseInvite(code);
        assertEquals(room, p.room());
        assertEquals(token, p.token());
        assertEquals("sig.example.com", p.host());
    }

    @Test public void invitationWithoutHost() {
        Protocol.Invite p = Protocol.parseInvite("r1." + "a".repeat(22) + "." + "b".repeat(22));
        assertNull(p.host());
    }

    @Test public void rejectsBadInvitations() {
        assertNull(Protocol.parseInvite("garbage"));
        assertNull(Protocol.parseInvite("r1.short.tok"));
        assertNull(Protocol.parseInvite(""));
        assertNull(Protocol.parseInvite(null));
    }

    @Test public void roleIsDeterministicAndSymmetric() {
        assertEquals("offerer", Protocol.roleFor("aaa", "bbb"));
        assertEquals("answerer", Protocol.roleFor("bbb", "aaa"));
        assertEquals("offerer", Protocol.roleFor("solo", null));
        String a = "peer-1111", b = "peer-2222";
        assertEquals(Protocol.roleFor(a, b).equals("offerer"), Protocol.roleFor(b, a).equals("answerer"));
    }

    @Test public void appMessageAndFrameParsing() {
        JSONObject m = Protocol.appMessage(7, "desktop", "hello");
        assertEquals("msg", m.getString("type"));
        assertEquals(7, m.getInt("id"));
        assertEquals("hello", m.getString("text"));
        // round-trips through the validator
        JSONObject parsed = Protocol.parseAppFrame(m.toString());
        assertEquals("hello", parsed.getString("text"));
        // wrong version and malformed are rejected
        assertNull(Protocol.parseAppFrame(new JSONObject().put("v", 2).put("type", "msg").put("text", "x").toString()));
        assertNull(Protocol.parseAppFrame("not json"));
        assertNull(Protocol.parseAppFrame(new JSONObject().put("v", 1).put("type", "msg").toString()));
        assertTrue(Protocol.parseAppFrame(Protocol.helloMessage("n", "desktop").toString()) != null);
        assertFalse(Protocol.randomPeerId().isEmpty());
    }
}
