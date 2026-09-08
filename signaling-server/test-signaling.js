const { spawn } = require('child_process');
const WebSocket = require('ws');
const http = require('http');

console.log('[Test] Starting signaling server test...');

const serverProc = spawn('node', ['server.js'], {
  cwd: __dirname,
  env: { ...process.env, PORT: '9099' },
  stdio: 'pipe'
});

serverProc.stdout.on('data', (d) => {
  const msg = d.toString();
  // console.log('[Server stdout]', msg);
});

serverProc.stderr.on('data', (d) => {
  console.error('[Server stderr]', d.toString());
});

setTimeout(() => {
  // Check HTTP endpoint
  http.get('http://localhost:9099/health', (res) => {
    let rawData = '';
    res.on('data', (chunk) => { rawData += chunk; });
    res.on('end', () => {
      console.log('[Test] /health returned:', rawData);

      // Now test WebSockets
      const wsUrl = 'ws://localhost:9099';
      const questWs = new WebSocket(wsUrl);
      const viewerWs = new WebSocket(wsUrl);

      questWs.on('open', () => {
        console.log('[Test] Quest WS connected');
        questWs.send(JSON.stringify({
          type: 'join',
          roomCode: 'Q3TEST',
          role: 'quest',
          localIp: '192.168.1.50'
        }));
      });

      viewerWs.on('open', () => {
        console.log('[Test] Viewer WS connected');
        viewerWs.send(JSON.stringify({
          type: 'join',
          roomCode: 'Q3TEST',
          role: 'viewer',
          localIp: '192.168.1.88'
        }));
      });

      let netReportReceived = false;

      viewerWs.on('message', (msg) => {
        const parsed = JSON.parse(msg.toString());
        console.log('[Test] Viewer received message:', parsed.type);
        if (parsed.type === 'network-topology') {
          console.log('[Test] Network Topology confirmed:', parsed.mode, parsed.description);
          netReportReceived = true;
          cleanup();
        }
      });

      function cleanup() {
        console.log('[Test] Verification passed successfully! Cleaning up...');
        questWs.close();
        viewerWs.close();
        serverProc.kill();
        process.exit(0);
      }

      setTimeout(() => {
        if (!netReportReceived) {
          console.error('[Test] Timeout waiting for network-topology message');
          serverProc.kill();
          process.exit(1);
        }
      }, 5000);
    });
  }).on('error', (e) => {
    console.error('[Test] HTTP health check failed:', e.message);
    serverProc.kill();
    process.exit(1);
  });
}, 1500);
