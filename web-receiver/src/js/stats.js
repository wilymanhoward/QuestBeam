/**
 * WebRTC Real-Time Performance & Topology Monitor
 * Inspects RTCPeerConnection statistics to determine:
 * 1. Network Routing Topology: Local LAN (Direct P2P host-to-host) vs Cloud (Relayed TURN/STUN)
 * 2. Real-time FPS, Bitrate (Mbps), Latency (RTT in ms), and Resolution
 */

class WebRTCStatsMonitor {
  constructor(peerConnection, onStatsUpdate) {
    this.pc = peerConnection;
    this.onStatsUpdate = onStatsUpdate;
    this.timer = null;
    this.prevBytesReceived = 0;
    this.prevTimestamp = 0;
    this.prevFramesDecoded = 0;
  }

  start(intervalMs = 1000) {
    this.stop();
    this.timer = setInterval(() => this.collectStats(), intervalMs);
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  async collectStats() {
    if (!this.pc || this.pc.connectionState !== 'connected') {
      return;
    }

    try {
      const stats = await this.pc.getStats();
      let activeCandidatePair = null;
      let localCandidate = null;
      let remoteCandidate = null;
      let videoInbound = null;

      stats.forEach(report => {
        if (report.type === 'transport' && report.selectedCandidatePairId) {
          activeCandidatePair = stats.get(report.selectedCandidatePairId);
        } else if (report.type === 'candidate-pair' && report.selected) {
          activeCandidatePair = report;
        }

        if (report.type === 'inbound-rtp' && report.kind === 'video') {
          videoInbound = report;
        }
      });

      if (activeCandidatePair) {
        localCandidate = stats.get(activeCandidatePair.localCandidateId);
        remoteCandidate = stats.get(activeCandidatePair.remoteCandidateId);
      }

      // Calculate Round-Trip Time (RTT)
      let rttMs = 0;
      if (activeCandidatePair && activeCandidatePair.currentRoundTripTime !== undefined) {
        rttMs = Math.round(activeCandidatePair.currentRoundTripTime * 1000);
      }

      // Determine Network Topology (LAN vs Cloud)
      let networkMode = 'unknown';
      let networkDescription = 'Detecting network topology...';

      if (localCandidate && remoteCandidate) {
        const localType = localCandidate.candidateType; // 'host', 'srflx', 'relay'
        const remoteType = remoteCandidate.candidateType;

        if (localType === 'host' && remoteType === 'host') {
          networkMode = 'lan';
          networkDescription = `Local Network (Direct P2P LAN via ${remoteCandidate.ip || 'Wi-Fi'})`;
        } else if (localType === 'relay' || remoteType === 'relay') {
          networkMode = 'cloud';
          networkDescription = 'Cloud Relay Mode (TURN routing between separate networks)';
        } else {
          networkMode = 'p2p-stun';
          networkDescription = 'Direct P2P NAT Traversal (STUN Reflexive)';
        }
      }

      // Calculate Bitrate (Mbps) and FPS
      let bitrateMbps = '0.0';
      let fps = 0;
      let width = 0;
      let height = 0;
      let codec = 'H.264';

      if (videoInbound) {
        width = videoInbound.frameWidth || 0;
        height = videoInbound.frameHeight || 0;

        const currentTimestamp = videoInbound.timestamp;
        const currentBytes = videoInbound.bytesReceived || 0;
        const currentFrames = videoInbound.framesDecoded || 0;

        if (this.prevTimestamp > 0) {
          const deltaMs = currentTimestamp - this.prevTimestamp;
          if (deltaMs > 0) {
            const deltaBits = (currentBytes - this.prevBytesReceived) * 8;
            bitrateMbps = (deltaBits / (deltaMs / 1000) / 1000000).toFixed(2);

            const deltaFrames = currentFrames - this.prevFramesDecoded;
            fps = Math.round(deltaFrames / (deltaMs / 1000));
          }
        }

        this.prevBytesReceived = currentBytes;
        this.prevTimestamp = currentTimestamp;
        this.prevFramesDecoded = currentFrames;
      }

      if (this.onStatsUpdate) {
        this.onStatsUpdate({
          networkMode,
          networkDescription,
          rttMs,
          bitrateMbps,
          fps,
          width,
          height,
          codec
        });
      }
    } catch (e) {
      console.warn('[WebRTCStats] Error querying stats:', e);
    }
  }
}
