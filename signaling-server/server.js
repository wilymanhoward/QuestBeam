const express = require('express');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const cors = require('cors');
const os = require('os');
const config = require('./config');

const app = express();
app.use(cors());
app.use(express.json());

// Helper to get local network IPv4 addresses of this host
function getLocalIpAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        addresses.push({ interface: name, address: iface.address });
      }
    }
  }
  return addresses;
}

// HTTP Endpoints
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    serverIps: getLocalIpAddresses()
  });
});

app.get('/config', (req, res) => {
  res.json({
    iceServers: config.iceServers,
    serverIps: getLocalIpAddresses()
  });
});

// Endpoint to inspect client IP
app.get('/ip', (req, res) => {
  const forwarded = req.headers['x-forwarded-for'];
  const remoteIp = forwarded ? forwarded.split(',')[0].trim() : req.socket.remoteAddress;
  res.json({ clientIp: remoteIp });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Rooms map: roomCode -> { quest: { ws, localIp, publicIp }, viewers: [ { ws, localIp, publicIp } ] }
const rooms = new Map();

function getClientPublicIp(req) {
  const forwarded = req.headers['x-forwarded-for'];
  return forwarded ? forwarded.split(',')[0].trim() : req.socket.remoteAddress;
}

// Compare network topology between Quest sender and laptop viewer
function evaluateNetworkTopology(questPeer, viewerPeer) {
  if (!questPeer || !viewerPeer) return { mode: 'unknown', reason: 'Waiting for peer' };

  const samePublicIp = questPeer.publicIp && viewerPeer.publicIp && 
                       (questPeer.publicIp === viewerPeer.publicIp || 
                        questPeer.publicIp === '127.0.0.1' || 
                        questPeer.publicIp === '::1');

  // Check private subnets (e.g. 192.168.1.x)
  let sameSubnet = false;
  if (questPeer.localIp && viewerPeer.localIp) {
    const qParts = questPeer.localIp.split('.');
    const vParts = viewerPeer.localIp.split('.');
    if (qParts.length === 4 && vParts.length === 4) {
      sameSubnet = (qParts[0] === vParts[0] && qParts[1] === vParts[1] && qParts[2] === vParts[2]);
    }
  }

  if (samePublicIp || sameSubnet) {
    return {
      mode: 'lan',
      description: 'Devices detected on the same Local Network (LAN)',
      directP2PPreferred: true
    };
  }

  return {
    mode: 'cloud',
    description: 'Devices on different networks. Routing via Cloud WebRTC / TURN Relay',
    directP2PPreferred: false
  };
}

wss.on('connection', (ws, req) => {
  const clientPublicIp = getClientPublicIp(req);
  let currentRoomCode = null;
  let currentRole = null;

  console.log(`[Signaling] New client connected from ${clientPublicIp}`);

  ws.on('message', (messageText) => {
    try {
      const data = JSON.parse(messageText);
      const { type, roomCode, role, payload, localIp } = data;

      switch (type) {
        case 'join': {
          const code = (roomCode || '').toUpperCase().trim();
          currentRoomCode = code;
          currentRole = role; // 'quest' or 'viewer'

          if (!rooms.has(code)) {
            rooms.set(code, { quest: null, viewers: [] });
          }
          const room = rooms.get(code);

          const peerInfo = { ws, localIp: localIp || null, publicIp: clientPublicIp };

          if (role === 'quest') {
            room.quest = peerInfo;
            console.log(`[Signaling] Room ${code}: Quest 3 registered (local: ${localIp}, public: ${clientPublicIp})`);
          } else {
            room.viewers.push(peerInfo);
            console.log(`[Signaling] Room ${code}: Viewer joined (local: ${localIp}, public: ${clientPublicIp})`);
          }

          // Acknowledge join
          ws.send(JSON.stringify({
            type: 'joined',
            roomCode: code,
            role,
            iceServers: config.iceServers
          }));

          // If both Quest and at least one viewer are present, evaluate network topology and notify
          if (room.quest && room.viewers.length > 0) {
            const latestViewer = room.viewers[room.viewers.length - 1];
            const netEval = evaluateNetworkTopology(room.quest, latestViewer);

            const netPayload = {
              type: 'network-topology',
              ...netEval,
              questIp: room.quest.localIp || room.quest.publicIp,
              viewerIp: latestViewer.localIp || latestViewer.publicIp
            };

            room.quest.ws.send(JSON.stringify(netPayload));
            latestViewer.ws.send(JSON.stringify(netPayload));

            // Notify Quest that a viewer is waiting for an offer
            room.quest.ws.send(JSON.stringify({
              type: 'viewer-ready',
              roomCode: code
            }));
          }
          break;
        }

        case 'offer': {
          // Relayed from Quest to Viewer(s)
          const room = rooms.get(currentRoomCode);
          if (room && room.viewers.length > 0) {
            console.log(`[Signaling] Relaying SDP Offer for room ${currentRoomCode}`);
            for (const viewer of room.viewers) {
              if (viewer.ws.readyState === WebSocket.OPEN) {
                viewer.ws.send(JSON.stringify({
                  type: 'offer',
                  payload
                }));
              }
            }
          }
          break;
        }

        case 'answer': {
          // Relayed from Viewer to Quest
          const room = rooms.get(currentRoomCode);
          if (room && room.quest && room.quest.ws.readyState === WebSocket.OPEN) {
            console.log(`[Signaling] Relaying SDP Answer for room ${currentRoomCode}`);
            room.quest.ws.send(JSON.stringify({
              type: 'answer',
              payload
            }));
          }
          break;
        }

        case 'ice-candidate': {
          // Relayed bi-directionally
          const room = rooms.get(currentRoomCode);
          if (room) {
            if (currentRole === 'quest') {
              for (const viewer of room.viewers) {
                if (viewer.ws.readyState === WebSocket.OPEN) {
                  viewer.ws.send(JSON.stringify({
                    type: 'ice-candidate',
                    payload
                  }));
                }
              }
            } else if (currentRole === 'viewer' && room.quest && room.quest.ws.readyState === WebSocket.OPEN) {
              room.quest.ws.send(JSON.stringify({
                type: 'ice-candidate',
                payload
              }));
            }
          }
          break;
        }

        case 'ping': {
          ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
          break;
        }

        default:
          console.log(`[Signaling] Unknown message type: ${type}`);
      }
    } catch (err) {
      console.error('[Signaling] Error parsing message:', err);
    }
  });

  ws.on('close', () => {
    console.log(`[Signaling] Client disconnected from room ${currentRoomCode} (${currentRole})`);
    if (currentRoomCode && rooms.has(currentRoomCode)) {
      const room = rooms.get(currentRoomCode);
      if (currentRole === 'quest') {
        room.quest = null;
        for (const viewer of room.viewers) {
          if (viewer.ws.readyState === WebSocket.OPEN) {
            viewer.ws.send(JSON.stringify({ type: 'peer-disconnected', peer: 'quest' }));
          }
        }
      } else {
        room.viewers = room.viewers.filter(v => v.ws !== ws);
        if (room.quest && room.quest.ws.readyState === WebSocket.OPEN) {
          room.quest.ws.send(JSON.stringify({ type: 'peer-disconnected', peer: 'viewer' }));
        }
      }

      if (!room.quest && room.viewers.length === 0) {
        rooms.delete(currentRoomCode);
        console.log(`[Signaling] Room ${currentRoomCode} deleted (empty)`);
      }
    }
  });
});

const PORT = config.port;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`=======================================================`);
  console.log(`Meta Quest 3 Signaling & Relay Server running on:`);
  console.log(`Local:   http://localhost:${PORT}`);
  for (const { interface: iface, address } of getLocalIpAddresses()) {
    console.log(`Network: http://${address}:${PORT} (${iface})`);
  }
  console.log(`WebSocket URL: ws://<SERVER-IP>:${PORT}`);
  console.log(`=======================================================`);
});
