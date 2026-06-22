package com.example.videocall;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

/**
 * Main video call activity that manages the WebRTC call session.
 * Implements both SignalR listener (for signaling) and WebRTC listener (for media events).
 */
public class VideoCallActivity extends AppCompatActivity
        implements SignalRClient.SignalRListener, WebRTCClient.WebRTCListener {

    private static final String TAG = "VideoCallActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    // Intent extras keys
    public static final String EXTRA_ROOM_ID = "roomId";
    public static final String EXTRA_USER_NAME = "userName";
    public static final String EXTRA_SERVER_URL = "serverUrl";

    // UI components
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private ImageButton btnToggleAudio;
    private ImageButton btnToggleVideo;
    private ImageButton btnEndCall;
    private ImageButton btnSwitchCamera;
    private TextView textStatus;

    // WebRTC and SignalR clients
    private EglBase eglBase;
    private WebRTCClient webRTCClient;
    private SignalRClient signalRClient;

    // Call state
    private String roomId;
    private String userName;
    private String serverUrl;
    private String remoteConnectionId;
    private boolean isAudioMuted = false;
    private boolean isVideoMuted = false;
    private boolean isCallActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during call
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Fullscreen immersive mode
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_video_call);

        // Extract intent extras
        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        userName = getIntent().getStringExtra(EXTRA_USER_NAME);
        serverUrl = getIntent().getStringExtra(EXTRA_SERVER_URL);

        if (roomId == null || userName == null || serverUrl == null) {
            Log.e(TAG, "Missing required intent extras");
            Toast.makeText(this, "Invalid call parameters", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupButtonListeners();

        // Check and request permissions before starting the call
        if (hasPermissions()) {
            initializeCall();
        } else {
            requestPermissions();
        }
    }

    /**
     * Initializes all UI view references.
     */
    private void initViews() {
        localVideoView = findViewById(R.id.local_video_view);
        remoteVideoView = findViewById(R.id.remote_video_view);
        btnToggleAudio = findViewById(R.id.btn_toggle_audio);
        btnToggleVideo = findViewById(R.id.btn_toggle_video);
        btnEndCall = findViewById(R.id.btn_end_call);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        textStatus = findViewById(R.id.text_status);
    }

    /**
     * Registers click listeners for all call control buttons.
     */
    private void setupButtonListeners() {
        btnToggleAudio.setOnClickListener(v -> toggleAudio());
        btnToggleVideo.setOnClickListener(v -> toggleVideo());
        btnEndCall.setOnClickListener(v -> endCall());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
    }

    /**
     * Initializes EGL, video renderers, WebRTC client, and SignalR connection.
     */
    private void initializeCall() {
        textStatus.setText(R.string.status_connecting);

        // Initialize EglBase for hardware-accelerated rendering
        eglBase = EglBase.create();

        // Initialize SurfaceViewRenderers
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setEnableHardwareScaler(true);
        localVideoView.setMirror(true);
        localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        localVideoView.setZOrderMediaOverlay(true);

        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setEnableHardwareScaler(true);
        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

        // Initialize WebRTC client
        webRTCClient = new WebRTCClient(this, eglBase, this);
        webRTCClient.initLocalMediaStream(this, localVideoView);

        // Initialize and connect SignalR
        signalRClient = new SignalRClient(serverUrl, this);
        signalRClient.connect();

        isCallActive = true;
    }

    // ==================== Button Handlers ====================

    private void toggleAudio() {
        if (webRTCClient == null) return;

        isAudioMuted = !isAudioMuted;
        webRTCClient.toggleAudio(isAudioMuted);

        // Update button appearance
        btnToggleAudio.setAlpha(isAudioMuted ? 0.5f : 1.0f);
        Log.d(TAG, "Audio " + (isAudioMuted ? "muted" : "unmuted"));
    }

    private void toggleVideo() {
        if (webRTCClient == null) return;

        isVideoMuted = !isVideoMuted;
        webRTCClient.toggleVideo(isVideoMuted);

        // Update button appearance and local video visibility
        btnToggleVideo.setAlpha(isVideoMuted ? 0.5f : 1.0f);
        localVideoView.setVisibility(isVideoMuted ? View.INVISIBLE : View.VISIBLE);
        Log.d(TAG, "Video " + (isVideoMuted ? "disabled" : "enabled"));
    }

    private void switchCamera() {
        if (webRTCClient != null) {
            webRTCClient.switchCamera();
        }
    }

    private void endCall() {
        Log.d(TAG, "End call pressed");
        isCallActive = false;
        Toast.makeText(this, R.string.msg_call_ended, Toast.LENGTH_SHORT).show();
        cleanupResources();
        finish();
    }

    // ==================== SignalRListener Implementation ====================

    @Override
    public void onConnected() {
        Log.d(TAG, "SignalR connected, joining room: " + roomId);
        textStatus.setText(R.string.status_waiting);
        signalRClient.joinRoom(roomId, userName);
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "SignalR disconnected");
        if (isCallActive) {
            textStatus.setText(R.string.status_disconnected);
        }
    }

    @Override
    public void onUserJoined(String connectionId, String joinedUserName, boolean isInitiator) {
        Log.d(TAG, "User joined: " + joinedUserName + " (" + connectionId + ") Initiator: " + isInitiator);
        Toast.makeText(this, getString(R.string.msg_user_joined, joinedUserName), Toast.LENGTH_SHORT).show();
        textStatus.setText(R.string.status_in_call);

        remoteConnectionId = connectionId;

        // Only the initiator should create the peer connection and send offer to the new user
        if (isInitiator) {
            webRTCClient.createPeerConnection();
            webRTCClient.createOffer();
        }
    }

    @Override
    public void onUserLeft(String connectionId, String leftUserName) {
        Log.d(TAG, "User left: " + leftUserName + " (" + connectionId + ")");
        Toast.makeText(this, getString(R.string.msg_user_left, leftUserName), Toast.LENGTH_SHORT).show();
        textStatus.setText(R.string.status_waiting);
        remoteConnectionId = null;

        // Clear remote video
        remoteVideoView.clearImage();
    }

    @Override
    public void onOfferReceived(String connectionId, String sdp) {
        Log.d(TAG, "Offer received from: " + connectionId);
        remoteConnectionId = connectionId;
        textStatus.setText(R.string.status_in_call);

        // Create peer connection, set remote offer, then create answer
        webRTCClient.createPeerConnection();
        webRTCClient.setRemoteDescription("offer", sdp);
        webRTCClient.createAnswer();
    }

    @Override
    public void onAnswerReceived(String connectionId, String sdp) {
        Log.d(TAG, "Answer received from: " + connectionId);

        // Set remote answer description
        webRTCClient.setRemoteDescription("answer", sdp);
    }

    @Override
    public void onIceCandidateReceived(String connectionId, String candidateJson) {
        Log.d(TAG, "ICE candidate received from: " + connectionId);

        // Add remote ICE candidate
        webRTCClient.addIceCandidate(candidateJson);
    }

    @Override
    public void onRoomFull() {
        Log.d(TAG, "Room is full");
        textStatus.setText(R.string.status_room_full);
        Toast.makeText(this, R.string.msg_room_full, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "SignalR error: " + error);
        Toast.makeText(this, getString(R.string.error_connection_failed, error), Toast.LENGTH_LONG).show();
    }

    // ==================== WebRTCListener Implementation ====================

    @Override
    public void onLocalStream(VideoTrack videoTrack) {
        Log.d(TAG, "Local stream available");
        // Local video is already being rendered via addSink in initLocalMediaStream
    }

    @Override
    public void onRemoteStream(VideoTrack videoTrack) {
        Log.d(TAG, "Remote stream available");
        runOnUiThread(() -> {
            videoTrack.addSink(remoteVideoView);
        });
    }

    @Override
    public void onIceCandidateGenerated(String candidateJson) {
        Log.d(TAG, "Sending ICE candidate to remote peer");
        if (remoteConnectionId != null && signalRClient != null) {
            signalRClient.sendIceCandidate(remoteConnectionId, candidateJson);
        } else {
            Log.w(TAG, "Cannot send ICE candidate: no remote connection ID");
        }
    }

    @Override
    public void onLocalDescription(org.webrtc.SessionDescription sdp) {
        Log.d(TAG, "Sending local description to remote peer: " + sdp.type.canonicalForm());
        if (remoteConnectionId != null && signalRClient != null) {
            if (sdp.type == org.webrtc.SessionDescription.Type.OFFER) {
                signalRClient.sendOffer(remoteConnectionId, sdp.description);
            } else if (sdp.type == org.webrtc.SessionDescription.Type.ANSWER) {
                signalRClient.sendAnswer(remoteConnectionId, sdp.description);
            }
        } else {
            Log.w(TAG, "Cannot send local description: no remote connection ID");
        }
    }

    @Override
    public void onConnectionStateChanged(String state) {
        Log.d(TAG, "WebRTC connection state: " + state);
        runOnUiThread(() -> {
            textStatus.setText(state);
        });
    }

    // ==================== Permissions ====================

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                initializeCall();
            } else {
                Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // ==================== Lifecycle & Cleanup ====================

    /**
     * Releases all WebRTC and SignalR resources.
     */
    private void cleanupResources() {
        if (signalRClient != null) {
            signalRClient.disconnect();
            signalRClient = null;
        }

        if (webRTCClient != null) {
            webRTCClient.close();
            webRTCClient = null;
        }

        if (localVideoView != null) {
            localVideoView.release();
        }

        if (remoteVideoView != null) {
            remoteVideoView.release();
        }

        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isCallActive = false;
        cleanupResources();
    }

    @Override
    public void onBackPressed() {
        // Confirm before ending call via back button
        endCall();
    }
}
