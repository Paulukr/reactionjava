# reactionjava — Reaction desktop client (P2P + legacy)

A JVM command-line client with the same two connection modes as the Android and browser clients,
sharing the same wire format (`../reactionjs/PROTOCOL.md`):

- **Legacy server**: msgserver HTTP protocol (`../msgserver`).
- **Peer-to-peer**: WebRTC DataChannel via [webrtc-java] (`dev.onvoid.webrtc`, Apache-2.0),
  Cloudflare-hosted signaling, public STUN. Signaling via [Java-WebSocket].

Interops with `../reaction` (Android) and `../reactionjs` (browser): the same invitation works
across all three, either side can create or join.

[webrtc-java]: https://github.com/devopvoid/webrtc-java
[Java-WebSocket]: https://github.com/TooTallNate/Java-WebSocket

## Build

```bash
./gradlew installDist          # -> build/install/reactionjava/bin/reactionjava
./gradlew test                 # protocol unit tests (5)
```

Toolchain: JDK 21 (pinned in `gradle.properties`). The native WebRTC library is pulled per host
platform via a classifier (`linux-x86_64`, `windows-x86_64`, `macos-x86_64`, `macos-aarch64`,
`linux-aarch64`); add the others as `runtimeOnly` in `build.gradle.kts` for a cross-platform
distribution.

## Run

```bash
BIN=build/install/reactionjava/bin/reactionjava

# Legacy msgserver mode
$BIN server http://<host-ip>:8765 from-app to-app

# P2P: create a room (prints an invitation) or join one
$BIN create https://your-worker.workers.dev --name laptop
$BIN join   r1.<room>.<token>@your-worker.workers.dev --name laptop
```

Type lines to send; received messages print as `<< #id from: text`. Ctrl-D quits.
`--stun <url>` overrides STUN; loopback/private signaling hosts use `ws://`, public hosts `wss://`.

## One-command integration test: `selftest`

The cheapest real-world check of a deployed Worker — one JVM, ~10 s, no test runner:

```bash
source ../reactionjs/scripts/deploy-env.sh        # REACTION_SIGNALING_URL + REACTION_AUTH (from users.local)
$BIN selftest "$REACTION_SIGNALING_URL" --auth "$REACTION_AUTH"
```

Steps, one line each, `PASS`/`FAIL` at the end and exit code 0/1: `GET /health`; `GET /` without
login → 401 (gate closed); with login → 200 + cookie; `POST /rooms` with login → 201; then two
**real WebRTC peers** in this JVM join that room through the signaling Durable Object + Cloudflare
STUN, the DataChannel opens on both sides and a message crosses each way. Verified 2026-09-22
against a local Worker with the gate (`PASS` with the login; without it the gate and room
creation fail as intended).

## Unit tests

- `ProtocolTest` — wire-format compatibility (invitation, role, message shape); mirrors the
  browser and Android protocol tests so all three agree.
- `P2pLoopbackTest` — runs the same `SelfTest` under JUnit; skipped unless `REACTION_SIGNALING`
  is set (`REACTION_AUTH` for a gated Worker):

  ```bash
  REACTION_SIGNALING=https://reaction.<subdomain>.workers.dev REACTION_AUTH=login:pw ./gradlew test --tests '*P2pLoopbackTest'
  ```

## Notes

- Data-only: no microphone/camera is requested.
- No TURN by default; add it (with short-lived credentials) only if STUN-only NAT traversal fails.
- The answerer can receive an already-OPEN DataChannel, so the client checks channel state right
  after registering its observer (not only on the state-change callback) — see `P2pClient`.
