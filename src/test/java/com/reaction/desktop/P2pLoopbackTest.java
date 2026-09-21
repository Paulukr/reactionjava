package com.reaction.desktop;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

/**
 * End-to-end P2P over a REAL WebRTC DataChannel and a live Reaction Worker, via {@link SelfTest}
 * (the same steps as `reactionjava selftest`). SKIPPED unless REACTION_SIGNALING is set, e.g.:
 *
 *   cd signaling && npx wrangler dev --port 8796 &      # local Worker (standalone copy, see README)
 *   REACTION_SIGNALING=http://127.0.0.1:8796 ./gradlew test --tests '*P2pLoopbackTest'
 *
 *   # or against the deployed Worker, with the site login:
 *   REACTION_SIGNALING=https://reaction.<subdomain>.workers.dev REACTION_AUTH=login:pw ./gradlew test --tests '*P2pLoopbackTest'
 */
public class P2pLoopbackTest {

    @Test(timeout = 90000)
    public void twoPeersExchangeOverDataChannel() {
        String signaling = System.getenv("REACTION_SIGNALING");
        assumeTrue("set REACTION_SIGNALING to run the live P2P test", signaling != null && !signaling.isBlank());
        SelfTest.Result r = new SelfTest(signaling, System.getenv("REACTION_AUTH"), 40).run();
        r.lines().forEach(System.err::println);
        assertTrue(String.join("\n", r.lines()), r.passed());
    }
}
