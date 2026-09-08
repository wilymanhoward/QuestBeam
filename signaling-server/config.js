// Configuration for Signaling & WebRTC ICE Servers
module.exports = {
  port: process.env.PORT || 8080,
  iceServers: [
    {
      urls: [
        'stun:stun.l.google.com:19302',
        'stun:stun1.l.google.com:19302',
        'stun:stun2.l.google.com:19302',
        'stun:stun.cloudflare.com:3478'
      ]
    },
    // Optional Cloud TURN server for relaying when devices are behind strict NAT or on separate networks
    ...(process.env.TURN_URL ? [{
      urls: process.env.TURN_URL,
      username: process.env.TURN_USERNAME || '',
      credential: process.env.TURN_CREDENTIAL || ''
    }] : [
      // Standard public testing relay / open relay fallback
      {
        urls: 'stun:stun.relay.metered.ca:80'
      }
    ])
  ]
};
