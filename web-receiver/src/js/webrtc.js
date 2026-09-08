/**
 * WebRTC Receiver Client for Meta Quest 3 Screen Cast
 * Handles SDP negotiation, ICE candidate routing, and stream attachment.
 */

class WebRTCReceiver {
  constructor(options = {}) {
    this.iceServers = options.iceServers || [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' }
    ];
    this.onIceCandidate = options.onIceCandidate || (() => {});
    this.onTrack = options.onTrack || (() => {});
    this.onConnectionStateChange = options.onConnectionStateChange || (() => {});
    this.onStatsUpdate = options.onStatsUpdate || (() => {});

    this.pc = null;
    this.statsMonitor = null;
    this.remoteStream = new MediaStream();
  }

  initPeerConnection() {
    this.close();

    const rtcConfig = {
      iceServers: this.iceServers,
      iceTransportPolicy: 'all', // Allows host, srflx, and relay candidates
      bundlePolicy: 'max-bundle'
    };

    console.log('[WebRTC] Initializing RTCPeerConnection with config:', rtcConfig);
    this.pc = new RTCPeerConnection(rtcConfig);

    this.pc.onicecandidate = (event) => {
      if (event.candidate) {
        // console.log('[WebRTC] Discovered local ICE candidate:', event.candidate.candidate);
        this.onIceCandidate(event.candidate);
      }
    };

    this.pc.onconnectionstatechange = () => {
      const state = this.pc.connectionState;
      console.log(`[WebRTC] Connection state changed: ${state}`);
      this.onConnectionStateChange(state);

      if (state === 'connected') {
        if (!this.statsMonitor) {
          this.statsMonitor = new WebRTCStatsMonitor(this.pc, this.onStatsUpdate);
          this.statsMonitor.start(1000);
        }
      } else if (state === 'disconnected' || state === 'failed' || state === 'closed') {
        if (this.statsMonitor) {
          this.statsMonitor.stop();
          this.statsMonitor = null;
        }
      }
    };

    this.pc.ontrack = (event) => {
      console.log('[WebRTC] Received remote track:', event.track.kind);
      this.remoteStream = new MediaStream();
      let stream = (event.streams && event.streams[0]) ? event.streams[0] : this.remoteStream;
      if (!event.streams || !event.streams[0]) {
        this.remoteStream.addTrack(event.track);
        stream = this.remoteStream;
      }

      // Tune video receiver for ultra-low latency playback (Quest 3 real-time mirror)
      if (event.receiver && 'jitterBufferTarget' in event.receiver) {
        try {
          event.receiver.jitterBufferTarget = 0; // 0ms jitter buffer target for instant display
          console.log('[WebRTC] Set jitterBufferTarget to 0ms');
        } catch (e) {
          console.warn('[WebRTC] Could not set jitterBufferTarget:', e);
        }
      }

      this.onTrack(stream, event.track);
    };

    return this.pc;
  }

  async handleOffer(offerSdp) {
    // ALWAYS cleanly tear down previous connection and create a fresh one for each new stream session
    this.close();
    this.initPeerConnection();

    console.log('[WebRTC] Setting remote description (Offer)...');
    await this.pc.setRemoteDescription(new RTCSessionDescription({
      type: 'offer',
      sdp: offerSdp
    }));

    console.log('[WebRTC] Creating Answer...');
    const answer = await this.pc.createAnswer();
    await this.pc.setLocalDescription(answer);

    return answer.sdp;
  }

  async addIceCandidate(candidateInit) {
    if (!this.pc) return;
    try {
      await this.pc.addIceCandidate(new RTCIceCandidate(candidateInit));
    } catch (e) {
      console.warn('[WebRTC] Error adding ICE candidate:', e);
    }
  }

  close() {
    if (this.statsMonitor) {
      this.statsMonitor.stop();
      this.statsMonitor = null;
    }
    if (this.pc) {
      try {
        this.pc.ontrack = null;
        this.pc.onicecandidate = null;
        this.pc.onconnectionstatechange = null;
        this.pc.close();
      } catch (e) {
        console.warn('[WebRTC] Error closing peer connection:', e);
      }
      this.pc = null;
    }
    this.remoteStream = new MediaStream();
  }
}
