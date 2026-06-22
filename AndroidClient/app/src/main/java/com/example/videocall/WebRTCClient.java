package com.example.videocall;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the WebRTC peer connection, including local media capture,
 * SDP offer/answer creation, and ICE candidate exchange.
 */
public class WebRTCClient {

    private static final String TAG = "WebRTCClient";

    private static final String VIDEO_TRACK_ID = "video_track_0";
    private static final String AUDIO_TRACK_ID = "audio_track_0";
    private static final String LOCAL_STREAM_ID = "local_stream";

    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int VIDEO_FPS = 30;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoSource localVideoSource;
    private AudioSource localAudioSource;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private final EglBase eglBase;
    private final Context context;
    private final WebRTCListener listener;
    private final Handler mainHandler;

    private boolean isAudioMuted = false;
    private boolean isVideoMuted = false;
    private boolean isUsingFrontCamera = true;

    /**
     * Callback interface for WebRTC events.
     * All methods are invoked on the main (UI) thread.
     */
    public interface WebRTCListener {
        void onLocalStream(VideoTrack videoTrack);
        void onRemoteStream(VideoTrack videoTrack);
        void onIceCandidateGenerated(String candidateJson);
        void onLocalDescription(SessionDescription sdp);
        void onConnectionStateChanged(String state);
    }

    /**
     * Creates a new WebRTCClient instance.
     *
     * @param context  The application context
     * @param eglBase  The shared EGL base for rendering
     * @param listener The listener for WebRTC events
     */
    public WebRTCClient(Context context, EglBase eglBase, WebRTCListener listener) {
        this.context = context;
        this.eglBase = eglBase;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        initializePeerConnectionFactory(context);
    }

    /**
     * Initializes the PeerConnectionFactory with video encoder/decoder support.
     */
    private void initializePeerConnectionFactory(Context context) {
        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials("")
                        .setEnableInternalTracer(false)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        Log.d(TAG, "PeerConnectionFactory initialized");
    }

    /**
     * Creates a PeerConnection with STUN servers and registers ICE/stream observers.
     */
    public void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "Signaling state: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE connection state: " + iceConnectionState);
                String state;
                switch (iceConnectionState) {
                    case CONNECTED:
                        state = "Connected";
                        break;
                    case DISCONNECTED:
                        state = "Disconnected";
                        break;
                    case FAILED:
                        state = "Failed";
                        break;
                    case CHECKING:
                        state = "Connecting...";
                        break;
                    case NEW:
                        state = "New";
                        break;
                    case COMPLETED:
                        state = "Completed";
                        break;
                    case CLOSED:
                        state = "Closed";
                        break;
                    default:
                        state = iceConnectionState.toString();
                        break;
                }
                mainHandler.post(() -> listener.onConnectionStateChanged(state));
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
                Log.d(TAG, "ICE receiving change: " + receiving);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "ICE gathering state: " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "ICE candidate generated: " + iceCandidate.sdpMid);
                try {
                    JSONObject json = new JSONObject();
                    json.put("sdpMid", iceCandidate.sdpMid);
                    json.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    json.put("candidate", iceCandidate.sdp);
                    String candidateJson = json.toString();
                    mainHandler.post(() -> listener.onIceCandidateGenerated(candidateJson));
                } catch (JSONException e) {
                    Log.e(TAG, "Error serializing ICE candidate", e);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "ICE candidates removed");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "Remote stream added: " + mediaStream.getId());
                if (!mediaStream.videoTracks.isEmpty()) {
                    VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                    mainHandler.post(() -> listener.onRemoteStream(remoteVideoTrack));
                }
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "Remote stream removed: " + mediaStream.getId());
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "Data channel: " + dataChannel.label());
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "Track added");
                if (rtpReceiver.track() instanceof VideoTrack) {
                    VideoTrack remoteVideoTrack = (VideoTrack) rtpReceiver.track();
                    mainHandler.post(() -> listener.onRemoteStream(remoteVideoTrack));
                }
            }
        });

        if (peerConnection == null) {
            Log.e(TAG, "Failed to create PeerConnection");
            mainHandler.post(() -> listener.onConnectionStateChanged("Error: Failed to create connection"));
            return;
        }

        // Add local tracks to peer connection
        if (localVideoTrack != null) {
            peerConnection.addTrack(localVideoTrack, List.of(LOCAL_STREAM_ID));
        }
        if (localAudioTrack != null) {
            peerConnection.addTrack(localAudioTrack, List.of(LOCAL_STREAM_ID));
        }

        Log.d(TAG, "PeerConnection created successfully");
    }

    /**
     * Initializes local media (camera + microphone) and starts capturing.
     *
     * @param context       The application context
     * @param localRenderer The SurfaceViewRenderer to display local video
     */
    public void initLocalMediaStream(Context context, SurfaceViewRenderer localRenderer) {
        // Create video capturer using Camera2
        videoCapturer = createCameraCapturer(context);
        if (videoCapturer == null) {
            Log.e(TAG, "Failed to create camera capturer");
            return;
        }

        // Create video source and track
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        localVideoSource = factory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, context, localVideoSource.getCapturerObserver());
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource);
        localVideoTrack.setEnabled(true);
        localVideoTrack.addSink(localRenderer);

        // Create audio source and track
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));

        localAudioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, localAudioSource);
        localAudioTrack.setEnabled(true);

        Log.d(TAG, "Local media stream initialized");
        mainHandler.post(() -> listener.onLocalStream(localVideoTrack));
    }

    /**
     * Creates a Camera2Capturer, preferring the front-facing camera.
     */
    private VideoCapturer createCameraCapturer(Context context) {
        Camera2Enumerator enumerator = new Camera2Enumerator(context);
        String[] deviceNames = enumerator.getDeviceNames();

        // Try front-facing camera first
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    isUsingFrontCamera = true;
                    return capturer;
                }
            }
        }

        // Fallback to back-facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    isUsingFrontCamera = false;
                    return capturer;
                }
            }
        }

        Log.e(TAG, "No camera found");
        return null;
    }

    /**
     * Creates an SDP offer and sets it as the local description.
     */
    public void createOffer() {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot create offer");
            return;
        }

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Offer created successfully");
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sd) {
                        // Not called for setLocalDescription
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set (offer)");
                        mainHandler.post(() -> listener.onLocalDescription(sessionDescription));
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        // Not called for setLocalDescription
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "Failed to set local description (offer): " + s);
                    }
                }, sessionDescription);
            }

            @Override
            public void onSetSuccess() {
                // Not called for createOffer
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "Failed to create offer: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                // Not called for createOffer
            }
        }, constraints);
    }

    /**
     * Creates an SDP answer and sets it as the local description.
     */
    public void createAnswer() {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot create answer");
            return;
        }

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Answer created successfully");
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sd) {
                        // Not called for setLocalDescription
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set (answer)");
                        mainHandler.post(() -> listener.onLocalDescription(sessionDescription));
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        // Not called for setLocalDescription
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "Failed to set local description (answer): " + s);
                    }
                }, sessionDescription);
            }

            @Override
            public void onSetSuccess() {
                // Not called for createAnswer
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "Failed to create answer: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                // Not called for createAnswer
            }
        }, constraints);
    }

    /**
     * Sets the remote session description (offer or answer).
     *
     * @param type The SDP type ("offer" or "answer")
     * @param sdp  The SDP string
     */
    public void setRemoteDescription(String type, String sdp) {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot set remote description");
            return;
        }

        SessionDescription.Type sdpType;
        if ("offer".equalsIgnoreCase(type)) {
            sdpType = SessionDescription.Type.OFFER;
        } else if ("answer".equalsIgnoreCase(type)) {
            sdpType = SessionDescription.Type.ANSWER;
        } else {
            Log.e(TAG, "Unknown SDP type: " + type);
            return;
        }

        SessionDescription sessionDescription = new SessionDescription(sdpType, sdp);

        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sd) {
                // Not called for setRemoteDescription
            }

            @Override
            public void onSetSuccess() {
                Log.d(TAG, "Remote description set successfully (" + type + ")");
            }

            @Override
            public void onCreateFailure(String s) {
                // Not called for setRemoteDescription
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "Failed to set remote description: " + s);
            }
        }, sessionDescription);
    }

    /**
     * Parses and adds a remote ICE candidate from a JSON string.
     *
     * @param candidateJson JSON string with keys: sdpMid, sdpMLineIndex, candidate
     */
    public void addIceCandidate(String candidateJson) {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot add ICE candidate");
            return;
        }

        try {
            JSONObject json = new JSONObject(candidateJson);
            String sdpMid = json.getString("sdpMid");
            int sdpMLineIndex = json.getInt("sdpMLineIndex");
            String candidate = json.getString("candidate");

            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(iceCandidate);
            Log.d(TAG, "ICE candidate added: " + sdpMid);
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing ICE candidate JSON", e);
        }
    }

    /**
     * Toggles the local audio track (mute/unmute).
     *
     * @param mute true to mute, false to unmute
     */
    public void toggleAudio(boolean mute) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!mute);
            isAudioMuted = mute;
            Log.d(TAG, "Audio " + (mute ? "muted" : "unmuted"));
        }
    }

    /**
     * Toggles the local video track (enable/disable camera).
     *
     * @param mute true to disable video, false to enable
     */
    public void toggleVideo(boolean mute) {
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(!mute);
            isVideoMuted = mute;
            Log.d(TAG, "Video " + (mute ? "disabled" : "enabled"));
        }
    }

    /**
     * Switches between front and back camera.
     */
    public void switchCamera() {
        if (videoCapturer instanceof Camera2Capturer) {
            Camera2Enumerator enumerator = new Camera2Enumerator(context);
            String[] deviceNames = enumerator.getDeviceNames();

            String targetDeviceName = null;
            for (String deviceName : deviceNames) {
                if (isUsingFrontCamera && enumerator.isBackFacing(deviceName)) {
                    targetDeviceName = deviceName;
                    break;
                } else if (!isUsingFrontCamera && enumerator.isFrontFacing(deviceName)) {
                    targetDeviceName = deviceName;
                    break;
                }
            }

            if (targetDeviceName != null) {
                final String switchTo = targetDeviceName;
                ((Camera2Capturer) videoCapturer).switchCamera(new Camera2Capturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean isFrontCamera) {
                        isUsingFrontCamera = isFrontCamera;
                        Log.d(TAG, "Camera switched to: " + (isFrontCamera ? "front" : "back"));
                    }

                    @Override
                    public void onCameraSwitchError(String error) {
                        Log.e(TAG, "Camera switch error: " + error);
                    }
                }, switchTo);
            }
        }
    }

    /**
     * Returns the local SDP description string, or null if not available.
     */
    public String getLocalDescription() {
        if (peerConnection != null && peerConnection.getLocalDescription() != null) {
            return peerConnection.getLocalDescription().description;
        }
        return null;
    }

    /**
     * Returns whether audio is currently muted.
     */
    public boolean isAudioMuted() {
        return isAudioMuted;
    }

    /**
     * Returns whether video is currently muted.
     */
    public boolean isVideoMuted() {
        return isVideoMuted;
    }

    /**
     * Closes and disposes all WebRTC resources.
     */
    public void close() {
        Log.d(TAG, "Closing WebRTC client...");

        try {
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
                videoCapturer = null;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Error stopping video capturer", e);
            Thread.currentThread().interrupt();
        }

        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }

        if (localVideoSource != null) {
            localVideoSource.dispose();
            localVideoSource = null;
        }

        if (localAudioSource != null) {
            localAudioSource.dispose();
            localAudioSource = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }

        if (factory != null) {
            factory.dispose();
            factory = null;
        }

        Log.d(TAG, "WebRTC client closed");
    }
}
