/**
 * Meta Quest 3 Web Receiver - Main Application Orchestrator
 */

function getDefaultSignalingUrl() {
  const saved = localStorage.getItem('quest_signaling_url');
  if (saved) return saved;

  const urlParams = new URLSearchParams(window.location.search);
  const sigParam = urlParams.get('sig');
  if (sigParam) return sigParam;

  if (window.location.protocol === 'https:') {
    // In HTTPS (e.g. questbeam.web.app), browsers block unencrypted ws://
    // Default to active Cloudflare Tunnel endpoint for remote streaming
    return localStorage.getItem('quest_cloud_tunnel_url') || 'wss://millions-great-representations-never.trycloudflare.com';
  }
  return `ws://${window.location.hostname || 'localhost'}:8080`;
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
  qrRoomCodeText: document.getElementById('qrRoomCodeText'),
  copyRoomCodeBadge: document.getElementById('copyRoomCodeBadge'),
  btnPresetLocal: document.getElementById('btnPresetLocal'),
  btnPresetCloud: document.getElementById('btnPresetCloud')
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

  if (mode === 'usb') {
    elements.networkPillText.textContent = rttMs ? `USB Cable (${rttMs}ms)` : '⚡ USB Cable (0ms)';
    elements.hudMode.textContent = '⚡ USB CABLE (0ms)';
    elements.hudMode.style.color = 'var(--accent-usb)';
    elements.statNetworkMode.textContent = '⚡ Ultra-Fast USB-C Cable (Direct Bus)';
    elements.statNetworkMode.style.color = 'var(--accent-usb)';
    elements.statNetworkDesc.textContent = desc || 'Direct hardware bus connection with 0ms delay & 0% packet loss';
  } else if (mode === 'lan') {
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

// Render Scannable QR Code onto Canvas
function renderQrCode(text) {
  const canvas = elements.qrCanvas;
  if (!canvas) return;
  const targetUrl = `https://questbeam.web.app/#room=${encodeURIComponent(text)}`;

  if (window.QRCode && typeof window.QRCode.toCanvas === 'function') {
    window.QRCode.toCanvas(canvas, targetUrl, {
      width: 180,
      margin: 1,
      color: {
        dark: '#0f172a',
        light: '#ffffff'
      }
    }, function (error) {
      if (error) console.error('QR code render error:', error);
    });
  } else {
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, 180, 180);
    ctx.fillStyle = '#0F172A';
    ctx.font = '14px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('Loading QR...', 90, 95);
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
          if (data.cloudUrl) {
            localStorage.setItem('quest_cloud_tunnel_url', data.cloudUrl);
          }
          break;

        case 'network-topology':
          console.log('[Signaling] Initial network topology:', data.mode, data.description);
          window.currentSignalingTopologyMode = data.mode;
          updateNetworkStatus(data.mode, data.description);
          const toastMsg = data.mode === 'usb' ? '⚡ USB Cable Direct (Zero Latency)' : (data.mode === 'lan' ? 'Direct Local LAN Detected' : 'Cloud Relay Mode');
          state.controls.showToast(`Network: ${toastMsg}`);
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

        case 'screenshot-ready':
          console.log('[Signaling] Received native HD screenshot from Quest 3');
          if (data.payload && data.payload.dataUrl) {
            const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
            const resText = data.payload.width && data.payload.height ? `${data.payload.width}x${data.payload.height}` : 'HD';
            const filename = `Quest3-HD-${resText}-${timestamp}.jpg`;

            const a = document.createElement('a');
            a.href = data.payload.dataUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);

            if (state.controls) {
              state.controls.onHdScreenshotReceived(filename, resText);
            }
          }
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
  let iceServers = [];
  if (state.iceServers && Array.isArray(state.iceServers) && state.iceServers.length > 0) {
    iceServers = state.iceServers;
  } else {
    iceServers = [
      { urls: state.stunUrl }
    ];
  }
  if (state.turnUrl && !iceServers.some(s => s.urls === state.turnUrl)) {
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
        
        // Failsafe: if video frames are decoding but standby screen is showing, make video visible
        if (elements.remoteVideo.srcObject && !elements.remoteVideo.classList.contains('visible')) {
          elements.remoteVideo.classList.add('visible');
          elements.standbyScreen.style.display = 'none';
          elements.viewportCard.classList.add('active-stream');
          state.isStreaming = true;
        }
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
  if (state.webrtc) {
    state.webrtc.close();
    state.webrtc = null;
  }
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

  // Hook up native HD screenshot requests from Meta Quest 3 hardware
  state.controls.onRequestHdScreenshot = () => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN && state.isStreaming) {
      console.log('[Signaling] Requesting native HD screenshot from Quest 3 in room:', state.roomCode);
      state.ws.send(JSON.stringify({
        type: 'request-screenshot',
        roomCode: state.roomCode,
        role: 'viewer'
      }));
      return true;
    }
    return false;
  };

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
    renderQrCode(state.roomCode);
    elements.modalQr.classList.add('open');
  });
  elements.btnCloseQr.addEventListener('click', () => {
    elements.modalQr.classList.remove('open');
  });
  if (elements.copyRoomCodeBadge) {
    elements.copyRoomCodeBadge.addEventListener('click', () => {
      navigator.clipboard.writeText(state.roomCode).then(() => {
        state.controls.showToast(`Room code [${state.roomCode}] copied to clipboard!`);
      }).catch(() => {
        state.controls.showToast(`Room code: ${state.roomCode}`);
      });
    });
  }

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

  // Preset Buttons in Settings
  if (elements.btnPresetLocal) {
    elements.btnPresetLocal.addEventListener('click', () => {
      elements.inputSignalingUrl.value = 'ws://localhost:8080';
    });
  }
  if (elements.btnPresetCloud) {
    elements.btnPresetCloud.addEventListener('click', () => {
      const cloud = localStorage.getItem('quest_cloud_tunnel_url') || 'wss://millions-great-representations-never.trycloudflare.com';
      elements.inputSignalingUrl.value = cloud;
    });
  }

  // Start Signaling Connection
  connectSignaling();
});
