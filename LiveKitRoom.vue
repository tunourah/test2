<template>
  <div class="room-root">
    <div class="videos">
      <!-- Remote video -->
      <video
        ref="remoteVideo"
        autoplay
        playsinline
        class="video remote"
      ></video>
 
      <video
        ref="localVideo"
        autoplay
        playsinline
        muted
        class="video local"
      ></video>
    </div>

    <!-- Debug overlay -->
    <div class="overlay">
      <div class="status">
        <div>Connection: <strong>{{ connected ? 'Connected' : 'Disconnected' }}</strong></div>
        <div>Muted: <strong>{{ isMuted ? 'Yes' : 'No' }}</strong></div>
        <div>Camera off: <strong>{{ cameraOff ? 'Yes' : 'No' }}</strong></div>
        <div>Room: <strong>{{ roomName || '-' }}</strong></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { Room, RoomEvent, Track , createLocalVideoTrack,createLocalAudioTrack} from 'livekit-client'

const room = ref(null)
const connected = ref(false)
const isMuted = ref(false)
const cameraOff = ref(false)
const roomName = ref('')

const remoteVideo = ref(null)
const localVideo = ref(null)

function attachVideoTrack(track, videoEl) {
  if (!videoEl || !track) return
  try {
    track.attach(videoEl)
  } catch (e) {
    console.error('Failed to attach track:', e)
  }
}

function detachAllTracks(videoEl) {
  if (!videoEl) return
  try {
    // LiveKit tracks add themselves as sources; detach all
    videoEl.srcObject = null
  } catch (e) {
    console.error('Failed to detach tracks:', e)
  }
}

/**
 * Java → Vue : called when Java sends a "startCall" command
 * payload = { wsUrl, token, roomName, video }
 */
async function handleStartCall(payload) {
  console.log('[Vue] handleStartCall payload:', payload)
  const { wsUrl, token, roomName: rn, video } = payload || {}
  roomName.value = rn || ''

  // Disconnect previous room if exists
  if (room.value) {
    try {
      await room.value.disconnect()
    } catch (e) {
      console.warn('Error disconnecting previous room:', e)
    }
    room.value = null
  }

  try {
    const newRoom = new Room({
      adaptiveStream: true,
      dynacast: true
    })

    // Remote track subscribed
   newRoom.on(RoomEvent.TrackSubscribed, (track, pub, participant) => {
  console.log(
    '[Vue] Subscribed:',
    track.kind,
    'from',
    participant.identity
  )

  if (track.kind === Track.Kind.Audio) {
    track.attach()
  }

  if (track.kind === Track.Kind.Video && remoteVideo.value) {
    track.attach(remoteVideo.value)
  }
})


    // Optional: local track published → attach to local video
    newRoom.on(RoomEvent.LocalTrackPublished, (publication, participant) => {
      console.log('[Vue] LocalTrackPublished:', publication.kind)
      if (publication.kind === Track.Kind.Video && localVideo.value) {
        const track = publication.track
        if (track) attachVideoTrack(track, localVideo.value)
      }
    })

    newRoom.on(RoomEvent.Disconnected, () => {
      console.log('[Vue] Room disconnected')
      connected.value = false
      window.javaBridge.sendToJava('callDisconnected', {})
      detachAllTracks(remoteVideo.value)
      detachAllTracks(localVideo.value)
    })

    console.log('[Vue] Connecting to LiveKit:', wsUrl)
    await newRoom.connect(wsUrl, token)
    connected.value = true
    room.value = newRoom

    // Enable mic and camera
   try {
  const micTrack = await createLocalAudioTrack()

  await newRoom.localParticipant.publishTrack(micTrack)
  isMuted.value = false

  console.log('✅ Audio track published')
} catch (e) {
  console.error('❌ Failed to create/publish audio track', e)
}


    if (video) {
  try {
    const camTrack = await createLocalVideoTrack({
      resolution: { width: 1280, height: 720 },
      frameRate: 30
    })

    await newRoom.localParticipant.publishTrack(camTrack)
    cameraOff.value = false

    if (localVideo.value) {
      camTrack.attach(localVideo.value)
    }

    console.log('✅ Video track published')
  } catch (e) {
    console.error('❌ Failed to create/publish video track', e)
    cameraOff.value = true
  }
} else {
  cameraOff.value = true
}


    // Notify Java
    window.javaBridge.sendToJava('callConnected', {
      roomName: roomName.value
    })

  } catch (e) {
    console.error('[Vue] Failed to connect to LiveKit:', e)
    connected.value = false
    window.javaBridge.sendToJava('callError', {
      message: e?.message || 'Unknown error'
    })
  }
}

/**
 * Java → Vue : toggle mute
 * payload = { mute: true/false }
 */
async function handleToggleMute(payload) {
  if (!room.value) return

  const mute = !!payload?.mute

  try {
    const audioPub =
      room.value.localParticipant.audioTrackPublications.values().next().value

    if (!audioPub?.track) return

    if (mute) {
      await audioPub.track.mute()
    } else {
      await audioPub.track.unmute()
    }

    isMuted.value = mute
  } catch (e) {
    console.error('[Vue] Failed to toggle mute:', e)
  }
}


/**
 * Java → Vue : toggle camera
 * payload = { off: true/false }
 */
async function handleToggleCamera(payload) {
  if (!room.value) return
  const off = !!payload?.off
  try {const videoPub = room.value.localParticipant.videoTrackPublications.values().next().value

if (off) {
  await videoPub?.track?.mute()
} else {
  await videoPub?.track?.unmute()
}
cameraOff.value = off

    cameraOff.value = off
  } catch (e) {
    console.error('[Vue] Failed to toggle camera:', e)
  }
}

/**
 * Java → Vue : end call
 */
async function handleEndCall() {
  if (!room.value) return
  try {
    await room.value.disconnect()
    room.value = null
    connected.value = false
    detachAllTracks(remoteVideo.value)
    detachAllTracks(localVideo.value)
  } catch (e) {
    console.error('[Vue] Failed to end call:', e)
  }
}
onMounted(async () => {
  console.log('[Vue] LiveKitRoom mounted')
   try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: true,
      audio: true
    })
    console.log('✅ Camera & Mic granted by browser', stream)

    stream.getTracks().forEach(t => t.stop())
  } catch (err) {
    console.error('❌ Camera/Mic permission denied', err)
  }

  function registerBridgeListeners(attempt = 1) {
    if (!window.javaBridge || typeof window.javaBridge.on !== 'function') {
      console.warn('[Vue] javaBridge not ready yet, retrying...', attempt)
      if (attempt < 30) {
        setTimeout(() => registerBridgeListeners(attempt + 1), 200)
      }
      return
    }

    console.log('[Vue] javaBridge ready, registering listeners', attempt)

    window.javaBridge.on('startCall', handleStartCall)
    window.javaBridge.on('toggleMute', handleToggleMute)
    window.javaBridge.on('toggleCamera', handleToggleCamera)
    window.javaBridge.on('endCall', handleEndCall)

     if (typeof window.javaBridge.sendToJava === 'function') {
      window.javaBridge.sendToJava('vueReady', {})
    }
  }

  registerBridgeListeners()
})


onBeforeUnmount(async () => {
  if (room.value) {
    try {
      await room.value.disconnect()
    } catch (e) {
      console.warn('Error disconnecting on unmount:', e)
    }
  }
})
</script>

<style scoped>
.room-root {
  position: relative;
  width: 100%;
  height: 100%;
  background: #000;
  overflow: hidden;
}

.videos {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;
}
video {
  width: 100%;
  height: 100%;
}

.video {
  background: #000;
  object-fit: cover;
}

.remote {
  width: 100%;
  height: 100%;
}

.local {
  position: absolute;
  width: 220px;
  height: 140px;
  bottom: 16px;
  right: 16px;
  border-radius: 8px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  box-shadow: 0 0 12px rgba(0, 0, 0, 0.7);
}

.overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
}

.status {
  margin: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.5);
  color: #f1f5f9;
  font-size: 12px;
  line-height: 1.4;
}
</style>
