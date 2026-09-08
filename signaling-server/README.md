# Meta Quest 3 Signaling & Relay Coordinator

This server manages:
1. **Room Pairing**: Connecting Meta Quest 3 with the Laptop Web Receiver via 6-character room codes (e.g., `Q3-9842`).
2. **Network Proximity Detection**: Analyzes local IPs and public gateway addresses to determine if the Quest and Laptop share the **same local Wi-Fi/LAN** or are on **different networks**.
3. **WebRTC Signaling**: Relays SDP Offers, SDP Answers, and ICE candidates between the headset and browser.
4. **Cloud Fallback Configuration**: Provides STUN and TURN configurations for NAT traversal and remote cloud streaming.

## Quick Start (Local Network)
Run this server on your laptop (or on a local machine):

```bash
cd signaling-server
npm install
npm start
```

The console will print:
```
Meta Quest 3 Signaling & Relay Server running on:
Local:   http://localhost:8080
Network: http://192.168.1.105:8080 (Wi-Fi)
WebSocket URL: ws://192.168.1.105:8080
```

## Cloud Deployment (For Streaming Across Different Networks)
You can deploy this server to any free/cheap Node.js host (Render, Fly.io, Railway, Heroku, or VPS):
1. Push this folder to a Git repository or deploy directly.
2. Optional Environment Variables for TURN Relay:
   - `TURN_URL`: `turn:your-turn-server.com:3478` (or Coturn server)
   - `TURN_USERNAME`: `username`
   - `TURN_CREDENTIAL`: `password`
   - `PORT`: `8080` (or host provided port)
