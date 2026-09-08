/**
 * Web Receiver Controls (Fullscreen, Local Recording, Snapshot, Audio)
 */

class StreamControls {
  constructor(videoElement, viewportCard) {
    this.video = videoElement;
    this.viewport = viewportCard;
    this.mediaRecorder = null;
    this.recordedChunks = [];
    this.isRecording = false;
    this.stream = null;

    this.initEventListeners();
  }

  setStream(mediaStream) {
    this.stream = mediaStream;
  }

  initEventListeners() {
    // Fullscreen Button
    const btnFullscreen = document.getElementById('btnFullscreen');
    if (btnFullscreen) {
      btnFullscreen.addEventListener('click', () => this.toggleFullscreen());
    }

    // Snapshot Button
    const btnSnapshot = document.getElementById('btnSnapshot');
    if (btnSnapshot) {
      btnSnapshot.addEventListener('click', () => this.captureSnapshot());
    }

    // Record Button
    const btnRecord = document.getElementById('btnRecord');
    if (btnRecord) {
      btnRecord.addEventListener('click', () => this.toggleRecording());
    }

    // Audio Toggle
    const btnAudio = document.getElementById('btnToggleAudio');
    if (btnAudio) {
      btnAudio.addEventListener('click', () => this.toggleAudio());
    }
  }

  toggleFullscreen() {
    if (!document.fullscreenElement) {
      if (this.viewport.requestFullscreen) {
        this.viewport.requestFullscreen();
      } else if (this.viewport.webkitRequestFullscreen) {
        this.viewport.webkitRequestFullscreen();
      }
      this.showToast('Entered Fullscreen');
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen();
      }
      this.showToast('Exited Fullscreen');
    }
  }

  captureSnapshot() {
    if (!this.video || !this.video.videoWidth) {
      this.showToast('No video stream active to capture');
      return;
    }

    const canvas = document.createElement('canvas');
    canvas.width = this.video.videoWidth;
    canvas.height = this.video.videoHeight;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(this.video, 0, 0, canvas.width, canvas.height);

    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filename = `Quest3-Capture-${timestamp}.png`;

    const a = document.createElement('a');
    a.href = canvas.toDataURL('image/png');
    a.download = filename;
    a.click();

    this.showToast(`Snapshot saved: ${filename}`);
  }

  toggleRecording() {
    const btnRecord = document.getElementById('btnRecord');

    if (!this.isRecording) {
      if (!this.stream) {
        this.showToast('No active stream to record');
        return;
      }

      try {
        const options = { mimeType: 'video/webm; codecs=vp9,opus' };
        let mimeType = 'video/webm; codecs=vp9,opus';
        if (!MediaRecorder.isTypeSupported(mimeType)) {
          mimeType = 'video/webm; codecs=vp8,opus';
          if (!MediaRecorder.isTypeSupported(mimeType)) {
            mimeType = 'video/webm';
          }
        }

        this.recordedChunks = [];
        this.mediaRecorder = new MediaRecorder(this.stream, { mimeType });

        this.mediaRecorder.ondataavailable = (event) => {
          if (event.data && event.data.size > 0) {
            this.recordedChunks.push(event.data);
          }
        };

        this.mediaRecorder.onstop = () => {
          const blob = new Blob(this.recordedChunks, { type: mimeType });
          const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
          const filename = `Quest3-Recording-${timestamp}.webm`;
          const url = URL.createObjectURL(blob);

          const a = document.createElement('a');
          a.href = url;
          a.download = filename;
          a.click();
          window.URL.revokeObjectURL(url);
          this.showToast(`Recording saved: ${filename}`);
        };

        this.mediaRecorder.start(1000);
        this.isRecording = true;
        btnRecord.classList.add('recording');
        this.showToast('Started video recording...');
      } catch (err) {
        console.error('Failed to start MediaRecorder:', err);
        this.showToast('Failed to record stream: ' + err.message);
      }
    } else {
      // Stop recording
      if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
        this.mediaRecorder.stop();
      }
      this.isRecording = false;
      btnRecord.classList.remove('recording');
    }
  }

  toggleAudio() {
    if (!this.video) return;
    this.video.muted = !this.video.muted;
    const isMuted = this.video.muted;

    const iconVolume = document.getElementById('iconVolume');
    if (iconVolume) {
      if (isMuted) {
        iconVolume.innerHTML = '<path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/>';
        this.showToast('Audio Muted');
      } else {
        iconVolume.innerHTML = '<path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/>';
        this.showToast('Audio Unmuted');
      }
    }
  }

  showToast(message, durationMs = 3000) {
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(10px)';
      toast.style.transition = 'all 0.3s ease';
      setTimeout(() => toast.remove(), 300);
    }, durationMs);
  }
}
