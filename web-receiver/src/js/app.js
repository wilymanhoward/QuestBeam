/**
 * Meta Quest 3 Web Receiver - Main Application Orchestrator
 */

function getDefaultSignalingUrl() {
  const saved = localStorage.getItem('quest_signaling_url');
  if (saved) return saved;
  if (window.location.hostname.includes('web.app') || window.location.hostname.includes('firebaseapp.com')) {
    return 'ws://localhost:8080';
  }
  return `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.hostname || 'localhost'}:8080`;
}

// Application State
const state = {
  roomCode: '',
  signalingUrl: getDefaultSignalingUrl(),
  stunUrl: localStorage.getItem('quest_stun_url') || 'stun:stun.l.google.com:19302',
  turnUrl: localStorage.getItem('quest_turn_url') || '',
  ws: null,
  webrtc: null,
  controls: null,
  isStreaming: false,
  networkMode: 'idle'
};

// UI Elements
const elements = {
  roomCodeBadge: document.getElementById('roomCodeBadge'),
  roomCodeText: document.getElementById('roomCodeText'),
  standbyRoomCode: document.getElementById('standbyRoomCode'),
  customRoomInput: document.getElementById('customRoomInput'),
  btnJoinRoom: document.getElementById('btnJoinRoom'),
  btnNewCode: document.getElementById('btnNewCode'),
  btnShowQr: document.getElementById('btnShowQr'),
  networkPill: document.getElementById('networkPill'),
  networkPillText: document.getElementById('networkPillText'),
  viewportCard: document.getElementById('viewportCard'),
  remoteVideo: document.getElementById('remoteVideo'),
  standbyScreen: document.getElementById('standbyScreen'),
  streamHud: document.getElementById('streamHud'),
  hudMode: document.getElementById('hudMode'),
  hudFps: document.getElementById('hudFps'),
  hudRtt: document.getElementById('hudRtt'),
  hudBitrate: document.getElementById('hudBitrate'),
  hudRes: document.getElementById('hudRes'),
  statNetworkMode: document.getElementById('statNetworkMode'),
  statNetworkDesc: document.getElementById('statNetworkDesc'),
  statRtt: document.getElementById('statRtt'),
  statBitrate: document.getElementById('statBitrate'),
  statResolution: document.getElementById('statResolution'),
  statFps: document.getElementById('statFps'),
  btnOpenSettings: document.getElementById('btnOpenSettings'),
  btnCloseSettings: document.getElementById('btnCloseSettings'),
  modalSettings: document.getElementById('modalSettings'),
  inputSignalingUrl: document.getElementById('inputSignalingUrl'),
  inputStunUrl: document.getElementById('inputStunUrl'),
  inputTurnUrl: document.getElementById('inputTurnUrl'),
  btnSaveSettings: document.getElementById('btnSaveSettings'),
  modalQr: document.getElementById('modalQr'),
  btnCloseQr: document.getElementById('btnCloseQr'),
  qrCanvas: document.getElementById('qrCanvas'),
  qrRoomCodeText: document.getElementById('qrRoomCodeText')
};

// Generate a clean 6-character room code (e.g. Q3-7842)
function generateRoomCode() {
  const num = Math.floor(1000 + Math.random() * 9000);
  return `Q3-${num}`;
}

// Update Room Code UI
function setRoomCode(code) {
  state.roomCode = code.toUpperCase().trim();
  elements.roomCodeText.textContent = state.roomCode;
  elements.standbyRoomCode.textContent = state.roomCode;
  elements.qrRoomCodeText.textContent = state.roomCode;
  localStorage.setItem('quest_room_code', state.roomCode);
  window.location.hash = `room=${state.roomCode}`;
  renderQrCode(state.roomCode);
}

// Update Network Status Pill & Badges
function updateNetworkStatus(mode, desc, rttMs) {
  state.networkMode = mode;
  elements.networkPill.className = `network-pill ${mode}`;

  if (mode === 'lan') {
    elements.networkPillText.textContent = rttMs ? `Local LAN (${rttMs}ms)` : 'Local LAN (Direct P2P)';
    elements.hudMode.textContent = 'LOCAL LAN';
    elements.hudMode.style.color = 'var(--accent-lan)';
    elements.statNetworkMode.textContent = 'Local Network (Direct LAN)';
    elements.statNetworkMode.style.color = 'var(--accent-lan)';
    elements.statNetworkDesc.textContent = desc || 'Ultra-low latency direct Wi-Fi P2P';
  } else if (mode === 'cloud') {
    elements.networkPillText.textContent = rttMs ? `Cloud Relay (${rttMs}ms)` : 'Cloud WebRTC Relay';
    elements.hudMode.textContent = 'CLOUD RELAY';
    elements.hudMode.style.color = 'var(--accent-cloud)';
    elements.statNetworkMode.textContent = 'Cloud WebRTC Relay';
    elements.statNetworkMode.style.color = 'var(--accent-cloud)';
    elements.statNetworkDesc.textContent = desc || 'Streaming across different networks via TURN';
  } else {
    elements.networkPillText.textContent = 'Standby';
    elements.hudMode.textContent = 'WAITING';
    elements.statNetworkMode.textContent = 'Disconnected';
    elements.statNetworkMode.style.color = 'var(--text-secondary)';
    elements.statNetworkDesc.textContent = 'Waiting for Quest 3 headset connection';
  }
}

// Render Simple Pure-JS QR Matrix on HTML Canvas (No heavy dependencies)
function renderQrCode(text) {
  const canvas = elements.qrCanvas;
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const size = 180;
  ctx.fillStyle = '#FFFFFF';
  ctx.fillRect(0, 0, size, size);

  // Generate pseudo-deterministic pattern from text string
  ctx.fillStyle = '#0F172A';
  const grid = 21;
  const cell = size / grid;

  // Corner finder markers
  function drawFinder(x, y) {
    ctx.fillRect(x * cell, y * cell, 7 * cell, 7 * cell);
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect((x + 1) * cell, (y + 1) * cell, 5 * cell, 5 * cell);
    ctx.fillStyle = '#0F172A';
    ctx.fillRect((x + 2) * cell, (y + 2) * cell, 3 * cell, 3 * cell);
  }

  drawFinder(0, 0);
  drawFinder(grid - 7, 0);
  drawFinder(0, grid - 7);

  // Body data cells based on room text hash
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (hash << 5) - hash + text.charCodeAt(i);
  }

  for (let r = 0; r < grid; r++) {
    for (let c = 0; c < grid; c++) {
      if ((r < 8 && c < 8) || (r < 8 && c >= grid - 8) || (r >= grid - 8 && c < 8)) continue;
      const bit = Math.sin((r * grid + c) * hash) > 0.15;
      if (bit) {
        ctx.fillRect(c * cell, r * cell, cell - 0.5, cell - 0.5);
      }
    }
  }
}

// WebSocket Signaling Client
function connectSignaling() {
  if (state.ws) {
    state.ws.close();
  }

  console.log(`[Signaling] Connecting to ${state.signalingUrl}...`);
  try {
    state.ws = new WebSocket(state.signalingUrl);
  } catch (err) {
    state.controls.showToast('Could not reach signaling server: ' + err.message);
    return;
  }

  state.ws.onopen = () => {
    console.log('[Signaling] Connected! Joining room:', state.roomCode);
    state.ws.send(JSON.stringify({
      type: 'join',
      roomCode: state.roomCode,
      role: 'viewer'
    }));
  };

  state.ws.onmessage = async (event) => {
    try {
      const data = JSON.parse(event.data);
      // console.log('[Signaling] Message received:', data.type);

      switch (data.type) {
        case 'joined':
          console.log('[Signaling] Successfully registered in room', data.roomCode);
          if (data.iceServers) {
            state.iceServers = data.iceServers;
          }
          break;

        case 'network-topology':
          console.log('[Signaling] Initial network topology:', data.mode, data.description);
          updateNetworkStatus(data.mode, data.description);
          state.controls.showToast(`Network: ${data.mode === 'lan' ? 'Direct LAN Detected' : 'Cloud Relay Mode'}`);
          break;

        case 'offer':
          console.log('[Signaling] Received SDP Offer from Quest 3');
          if (!state.webrtc) {
            initWebRTC();
          }
          const answerSdp = await state.webrtc.handleOffer(data.payload.sdp);
          state.ws.send(JSON.stringify({
            type: 'answer',
            roomCode: state.roomCode,
            role: 'viewer',
            payload: { sdp: answerSdp }
          }));
          break;

        case 'ice-candidate':
          if (state.webrtc && data.payload) {
            await state.webrtc.addIceCandidate(data.payload);
          }
          break;

        case 'peer-disconnected':
          console.log('[Signaling] Quest 3 disconnected');
          handleStreamStop('Headset disconnected');
          break;
      }
    } catch (err) {
      console.error('[Signaling] Error parsing message:', err);
    }
  };

  state.ws.onclose = () => {
    console.log('[Signaling] Connection closed');
  };

  state.ws.onerror = (e) => {
    console.warn('[Signaling] Connection error:', e);
  };
}

// Initialize WebRTC receiver
function initWebRTC() {
  const iceServers = [
    { urls: state.stunUrl }
  ];
  if (state.turnUrl) {
    iceServers.push({ urls: state.turnUrl });
  }

  state.webrtc = new WebRTCReceiver({
    iceServers,
    onIceCandidate: (candidate) => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({
          type: 'ice-candidate',
          roomCode: state.roomCode,
          role: 'viewer',
          payload: candidate
        }));
      }
    },
    onTrack: (stream, track) => {
      console.log('[App] Remote video track attached to video element:', track.kind);
      elements.remoteVideo.srcObject = stream;
      elements.remoteVideo.muted = true;
      elements.remoteVideo.play().then(() => {
        console.log('[App] Video playback started successfully');
      }).catch(err => {
        console.warn('[App] Autoplay issue, user interaction may be required:', err);
      });
      elements.remoteVideo.classList.add('visible');
      elements.standbyScreen.style.display = 'none';
      elements.viewportCard.classList.add('active-stream');
      state.isStreaming = true;
      state.controls.setStream(stream);
      state.controls.showToast('Quest 3 Screen Mirror Connected!');
    },
    onConnectionStateChange: (connectionState) => {
      if (connectionState === 'disconnected' || connectionState === 'failed') {
        handleStreamStop('Streaming connection lost');
      }
    },
    onStatsUpdate: (stats) => {
      // Real-time HUD & Stats Card updates
      elements.hudFps.textContent = stats.fps;
      elements.hudRtt.textContent = `${stats.rttMs} ms`;
      elements.hudBitrate.textContent = `${stats.bitrateMbps} Mbps`;
      if (stats.width > 0) {
        elements.hudRes.textContent = `${stats.width}x${stats.height}`;
        elements.statResolution.textContent = `${stats.width}x${stats.height}`;
      }
      elements.statFps.textContent = `${stats.fps} fps (${stats.codec})`;
      elements.statRtt.textContent = `${stats.rttMs} ms`;
      elements.statBitrate.textContent = `${stats.bitrateMbps} Mbps`;

      if (stats.networkMode !== 'unknown') {
        updateNetworkStatus(stats.networkMode, stats.networkDescription, stats.rttMs);
      }
    }
  });

  state.webrtc.initPeerConnection();
}

function handleStreamStop(reason) {
  state.isStreaming = false;
  elements.remoteVideo.classList.remove('visible');
  elements.remoteVideo.srcObject = null;
  elements.standbyScreen.style.display = 'flex';
  elements.viewportCard.classList.remove('active-stream');
  updateNetworkStatus('idle', reason || 'Disconnected');
  if (state.controls) {
    state.controls.showToast(reason || 'Casting stopped');
  }
}

// Event Listeners & Bootstrapping
document.addEventListener('DOMContentLoaded', () => {
  // Initialize Stream Controls
  state.controls = new StreamControls(elements.remoteVideo, elements.viewportCard);

  // Check URL hash for pre-selected room code (e.g. #room=Q3-CAST), or use saved / default
  const hashMatch = window.location.hash.match(/room=([A-Za-z0-9_-]+)/);
  const initialCode = hashMatch ? hashMatch[1] : (localStorage.getItem('quest_room_code') || 'Q3-CAST');
  setRoomCode(initialCode);

  // Load Settings into inputs
  elements.inputSignalingUrl.value = state.signalingUrl;
  elements.inputStunUrl.value = state.stunUrl;
  elements.inputTurnUrl.value = state.turnUrl;

  // Copy Room Code
  elements.roomCodeBadge.addEventListener('click', () => {
    navigator.clipboard.writeText(state.roomCode);
    state.controls.showToast(`Room code "${state.roomCode}" copied to clipboard!`);
  });

  // Join Custom Room
  elements.btnJoinRoom.addEventListener('click', () => {
    const custom = elements.customRoomInput.value.trim();
    if (custom) {
      setRoomCode(custom);
      connectSignaling();
      elements.customRoomInput.value = '';
      state.controls.showToast(`Joined room: ${state.roomCode}`);
    }
  });

  // Random Code
  elements.btnNewCode.addEventListener('click', () => {
    setRoomCode(generateRoomCode());
    connectSignaling();
    state.controls.showToast(`New room generated: ${state.roomCode}`);
  });

  // QR Modal
  elements.btnShowQr.addEventListener('click', () => {
    elements.modalQr.classList.add('open');
  });
  elements.btnCloseQr.addEventListener('click', () => {
    elements.modalQr.classList.remove('open');
  });

  // Settings Modal
  elements.btnOpenSettings.addEventListener('click', () => {
    elements.modalSettings.classList.add('open');
  });
  elements.btnCloseSettings.addEventListener('click', () => {
    elements.modalSettings.classList.remove('open');
  });
  elements.btnSaveSettings.addEventListener('click', () => {
    state.signalingUrl = elements.inputSignalingUrl.value.trim();
    state.stunUrl = elements.inputStunUrl.value.trim();
    state.turnUrl = elements.inputTurnUrl.value.trim();

    localStorage.setItem('quest_signaling_url', state.signalingUrl);
    localStorage.setItem('quest_stun_url', state.stunUrl);
    localStorage.setItem('quest_turn_url', state.turnUrl);

    elements.modalSettings.classList.remove('open');
    state.controls.showToast('Settings saved. Reconnecting...');
    connectSignaling();
  });

  // Start Signaling Connection
  connectSignaling();
});
