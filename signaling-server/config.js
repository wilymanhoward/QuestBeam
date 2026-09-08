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
    // Cloud TURN servers for media relaying when devices are on separate networks or behind symmetric NAT
    ...(process.env.TURN_URL ? [{
      urls: process.env.TURN_URL,
      username: process.env.TURN_USERNAME || '',
      credential: process.env.TURN_CREDENTIAL || ''
    }] : [
      {
        urls: [
          'turn:openrelay.metered.ca:80',
          'turn:openrelay.metered.ca:443',
          'turn:openrelay.metered.ca:443?transport=tcp'
        ],
        username: process.env.TURN_USERNAME || 'openrelayproject',
        credential: process.env.TURN_CREDENTIAL || 'openrelayproject'
      }
    ])
  ]
};
