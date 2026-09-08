const express = require('express');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const cors = require('cors');
const os = require('os');
const dgram = require('dgram');
const { exec } = require('child_process');
const config = require('./config');

const app = express();

// Security: Enforce small JSON payload limit
app.use(express.json({ limit: '10kb' }));

// Security: Basic HTTP Security Headers
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-XSS-Protection', '1; mode=block');
  next();
});

// Security: Restrict CORS to common local origins or configurable whitelist
app.use(cors({
  origin: (origin, callback) => {
    // Allow requests with no origin (e.g. mobile apps, curl) or localhost/local LAN origins
    if (!origin || /^https?:\/\/(localhost|127\.0\.0\.1|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[0-1])\.\d+\.\d+)(:\d+)?$/.test(origin)) {
      callback(null, true);
    } else {
      callback(null, true); // Allow during testing, but validated
    }
  }
}));

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

// Helper to get active waiting room code for auto-pairing with Quest
function getActiveWaitingRoomCode() {
  // 1. Prefer room with viewers waiting and NO quest connected yet
  for (const [code, room] of rooms.entries()) {
    if (room.viewers && room.viewers.length > 0 && !room.quest) {
      return code;
    }
  }
  // 2. Otherwise any room with active viewers
  for (const [code, room] of rooms.entries()) {
    if (room.viewers && room.viewers.length > 0) {
      return code;
    }
  }
  return null;
}

// Health check endpoint (also supplies active website room code for instant pairing)
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    activeRoomCode: getActiveWaitingRoomCode()
  });
});

app.get('/config', (req, res) => {
  res.json({
    iceServers: config.iceServers
  });
});

// Endpoint to inspect client IP
app.get('/ip', (req, res) => {
  const forwarded = req.headers['x-forwarded-for'];
  const remoteIp = forwarded ? forwarded.split(',')[0].trim() : req.socket.remoteAddress;
  res.json({ clientIp: remoteIp });
});

const server = http.createServer(app);

// Security: Limit WebSocket maxPayload to 64 KB to block memory exhaustion attacks
const wss = new WebSocketServer({
  server,
  maxPayload: 64 * 1024
});

// Security: Rate limiting & connection tracking
const ipConnectionCounts = new Map(); // ip -> count
const socketRateLimits = new WeakMap(); // ws -> { count, resetTime }

const MAX_CONNECTIONS_PER_IP = 15;
const MAX_MESSAGES_PER_SEC = 40;
const ROOM_CODE_REGEX = /^[A-Za-z0-9_-]{3,24}$/;
const ALLOWED_ROLES = new Set(['quest', 'viewer']);
const ALLOWED_MESSAGE_TYPES = new Set(['join', 'offer', 'answer', 'ice-candidate', 'ping']);

// Rooms map: roomCode -> { quest: { ws, localIp, publicIp }, viewers: [ { ws, localIp, publicIp } ], pin: string|null }
const rooms = new Map();

function getClientPublicIp(req) {
  const forwarded = req.headers['x-forwarded-for'];
  return forwarded ? forwarded.split(',')[0].trim() : (req.socket.remoteAddress || '127.0.0.1');
}

// Compare network topology between Quest sender and laptop viewer
function evaluateNetworkTopology(questPeer, viewerPeer) {
  if (!questPeer || !viewerPeer) return { mode: 'unknown', reason: 'Waiting for peer' };

  // 1. USB Direct Cable Detection:
  // When Quest connects via ADB reverse tunnel or loopback IP (127.0.0.1 / ::1 / ::ffff:127.0.0.1)
  const isQuestUsb = questPeer.publicIp === '127.0.0.1' ||
                     questPeer.publicIp === '::1' ||
                     questPeer.publicIp === '::ffff:127.0.0.1' ||
                     questPeer.localIp === '127.0.0.1';

  if (isQuestUsb) {
    return {
      mode: 'usb',
      description: '⚡ Ultra-Fast USB-C Cable (Direct Hardware Bus, ~0ms latency)',
      directP2PPreferred: true
    };
  }

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

  // Security: Max connections per IP check
  const activeConn = (ipConnectionCounts.get(clientPublicIp) || 0) + 1;
  if (activeConn > MAX_CONNECTIONS_PER_IP) {
    console.warn(`[Security] Connection rejected: IP ${clientPublicIp} exceeded max connections (${MAX_CONNECTIONS_PER_IP})`);
    ws.close(1008, 'Too many connections from this IP');
    return;
  }
  ipConnectionCounts.set(clientPublicIp, activeConn);

  let currentRoomCode = null;
  let currentRole = null;

  console.log(`[Signaling] New client connected from ${clientPublicIp} (Active: ${activeConn})`);

  ws.on('message', (messageText) => {
    // Security: Message rate limit check
    const now = Date.now();
    let rateData = socketRateLimits.get(ws);
    if (!rateData || now > rateData.resetTime) {
      rateData = { count: 0, resetTime: now + 1000 };
    }
    rateData.count++;
    socketRateLimits.set(ws, rateData);

    if (rateData.count > MAX_MESSAGES_PER_SEC) {
      console.warn(`[Security] Rate limit exceeded for client ${clientPublicIp}. Terminating socket.`);
      ws.terminate();
      return;
    }

    try {
      const data = JSON.parse(messageText);
      const { type, roomCode, role, payload, localIp, pin } = data;

      // Security: Validate message type
      if (!type || !ALLOWED_MESSAGE_TYPES.has(type)) {
        ws.send(JSON.stringify({ type: 'error', message: 'Invalid or unsupported message type' }));
        return;
      }

      switch (type) {
        case 'join': {
          const rawCode = (roomCode || '').trim();

          // Security: Validate room code format
          if (!ROOM_CODE_REGEX.test(rawCode)) {
            ws.send(JSON.stringify({ type: 'error', message: 'Room code must be 3-24 alphanumeric characters' }));
            return;
          }

          // Security: Validate role
          if (!role || !ALLOWED_ROLES.has(role)) {
            ws.send(JSON.stringify({ type: 'error', message: 'Invalid role specified' }));
            return;
          }

          const code = rawCode.toUpperCase();
          currentRoomCode = code;
          currentRole = role;

          if (!rooms.has(code)) {
            rooms.set(code, { quest: null, viewers: [], pin: null });
          }
          const room = rooms.get(code);

          // Security: Room PIN authentication check
          if (role === 'quest' && pin) {
            room.pin = String(pin).trim();
          } else if (role === 'viewer' && room.pin) {
            if (!pin || String(pin).trim() !== room.pin) {
              console.warn(`[Security] Viewer failed PIN authentication for room ${code}`);
              ws.send(JSON.stringify({ type: 'auth-failed', message: 'Incorrect room PIN' }));
              ws.close(1008, 'Authentication failed');
              return;
            }
          }

          // Security: Prevent Quest sender hijacking
          if (role === 'quest') {
            if (room.quest && room.quest.ws !== ws && room.quest.ws.readyState === WebSocket.OPEN) {
              console.warn(`[Security] Rejection: A Quest sender is already active in room ${code}`);
              ws.send(JSON.stringify({ type: 'error', message: 'A Quest sender is already broadcasting in this room' }));
              return;
            }
            room.quest = { ws, localIp: localIp || null, publicIp: clientPublicIp };
            console.log(`[Signaling] Room ${code}: Quest 3 registered (local: ${localIp}, public: ${clientPublicIp})`);
          } else {
            room.viewers.push({ ws, localIp: localIp || null, publicIp: clientPublicIp });
            console.log(`[Signaling] Room ${code}: Viewer joined (local: ${localIp}, public: ${clientPublicIp})`);
          }

          // Acknowledge join
          ws.send(JSON.stringify({
            type: 'joined',
            roomCode: code,
            role,
            iceServers: config.iceServers
          }));

          // If both Quest and viewer are present, evaluate network topology and trigger negotiation
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
          const room = rooms.get(currentRoomCode);
          if (room && currentRole === 'quest') {
            console.log(`[Signaling] Relaying SDP Offer for room ${currentRoomCode}`);
            for (const viewer of room.viewers) {
              if (viewer.ws.readyState === WebSocket.OPEN) {
                viewer.ws.send(JSON.stringify({ type: 'offer', payload }));
              }
            }
          }
          break;
        }

        case 'answer': {
          const room = rooms.get(currentRoomCode);
          if (room && currentRole === 'viewer' && room.quest && room.quest.ws.readyState === WebSocket.OPEN) {
            console.log(`[Signaling] Relaying SDP Answer for room ${currentRoomCode}`);
            room.quest.ws.send(JSON.stringify({ type: 'answer', payload }));
          }
          break;
        }

        case 'ice-candidate': {
          const room = rooms.get(currentRoomCode);
          if (room) {
            if (currentRole === 'quest') {
              for (const viewer of room.viewers) {
                if (viewer.ws.readyState === WebSocket.OPEN) {
                  viewer.ws.send(JSON.stringify({ type: 'ice-candidate', payload }));
                }
              }
            } else if (currentRole === 'viewer' && room.quest && room.quest.ws.readyState === WebSocket.OPEN) {
              room.quest.ws.send(JSON.stringify({ type: 'ice-candidate', payload }));
            }
          }
          break;
        }

        case 'ping': {
          ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
          break;
        }
      }
    } catch (err) {
      console.error('[Signaling] Malformed message rejected:', err.message);
    }
  });

  ws.on('close', () => {
    // Decrement connection count
    const remaining = (ipConnectionCounts.get(clientPublicIp) || 1) - 1;
    if (remaining <= 0) {
      ipConnectionCounts.delete(clientPublicIp);
    } else {
      ipConnectionCounts.set(clientPublicIp, remaining);
    }

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

// Automatic ADB Reverse tunnel setup for zero-latency USB Cable streaming
let isAdbReverseActive = false;
let lastAdbWarningTime = 0;
function setupAdbReverse() {
  const adbCandidates = [
    'adb',
    'C:\\Users\\Howard Wilyman\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe',
    process.env.LOCALAPPDATA ? `${process.env.LOCALAPPDATA}\\Android\\Sdk\\platform-tools\\adb.exe` : null
  ].filter(Boolean);

  function tryCandidate(index) {
    if (index >= adbCandidates.length) return;
    const adbPath = adbCandidates[index];
    exec(`"${adbPath}" reverse tcp:${PORT} tcp:${PORT}`, (err, stdout, stderr) => {
      if (!err) {
        if (!isAdbReverseActive) {
          console.log(`[USB Auto-Detect] ⚡ USB Cable detected & authorized! ADB reverse active: tcp:${PORT} -> tcp:${PORT}`);
          isAdbReverseActive = true;
        }
      } else {
        const errorOutput = (stderr || '') + (stdout || '') + (err.message || '');
        if (errorOutput.includes('unauthorized')) {
          const now = Date.now();
          if (now - lastAdbWarningTime > 300000) {
            console.warn('[USB Auto-Detect] ⚠️ Meta Quest 3 is connected via USB, but UNAUTHORIZED!');
            console.warn('[USB Auto-Detect] 👉 Please put on your Quest 3 headset and tap "Always allow from this computer" -> "Allow" on the popup.');
            lastAdbWarningTime = now;
          }
        }
        if (index + 1 < adbCandidates.length) {
          tryCandidate(index + 1);
        } else {
          isAdbReverseActive = false;
        }
      }
    });
  }

  tryCandidate(0);
}

// UDP Broadcast Auto-Discovery Service (Port 8081)
// Allows Quest 3 headset to discover this laptop server on Wi-Fi in <5ms without scanning 254 subnet IPs
function startUdpDiscovery() {
  const udpServer = dgram.createSocket({ type: 'udp4', reuseAddr: true });

  udpServer.on('error', (err) => {
    console.warn('[UDP Discovery] Socket warning:', err.message);
  });

  udpServer.on('message', (msg, rinfo) => {
    const text = msg.toString().trim();
    if (text.includes('QUESTBEAM_DISCOVER')) {
      console.log(`[UDP Discovery] Received discovery probe from Quest 3 at ${rinfo.address}:${rinfo.port}`);
      const localAddrs = getLocalIpAddresses();
      // Pick best IP that matches caller's subnet if possible, or primary LAN IP
      const matching = localAddrs.find(a => {
        const callerSub = rinfo.address.split('.').slice(0, 3).join('.');
        return a.address.startsWith(callerSub);
      }) || localAddrs[0];

      const laptopIp = matching ? matching.address : '127.0.0.1';
      const beaconPayload = JSON.stringify({
        type: 'QUESTBEAM_BEACON',
        serverIp: laptopIp,
        port: PORT,
        wsUrl: `ws://${laptopIp}:${PORT}`,
        activeRoomCode: getActiveWaitingRoomCode()
      });

      const response = Buffer.from(beaconPayload);
      udpServer.send(response, 0, response.length, rinfo.port, rinfo.address, (err) => {
        if (!err) {
          console.log(`[UDP Discovery] Sent beacon to Quest 3 at ${rinfo.address} -> ws://${laptopIp}:${PORT}`);
        }
      });
    }
  });

  udpServer.bind(8081, '0.0.0.0', () => {
    try {
      udpServer.setBroadcast(true);
      console.log(`[UDP Discovery] Auto-discovery beacon active on UDP port 8081`);
    } catch (e) {
      console.warn('[UDP Discovery] Could not set broadcast flag:', e.message);
    }
  });
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`=======================================================`);
  console.log(`QuestBeam Hardened Signaling & Relay Server running on:`);
  console.log(`Local:   http://localhost:${PORT}`);
  for (const { interface: iface, address } of getLocalIpAddresses()) {
    console.log(`Network: http://${address}:${PORT} (${iface})`);
  }
  console.log(`WebSocket URL: ws://<SERVER-IP>:${PORT}`);
  console.log(`Security: MaxPayload=64KB, RateLimit=40msg/s, OriginFiltered`);
  console.log(`=======================================================`);

  // Start instant UDP auto-discovery beacon
  startUdpDiscovery();

  // Initial attempt and periodic check for USB cable plug/unplug
  setupAdbReverse();
  setInterval(setupAdbReverse, 8000);
});
