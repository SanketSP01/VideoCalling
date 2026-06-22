package com.example.videocall;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;
import com.microsoft.signalr.HubConnectionState;
import com.microsoft.signalr.TransportEnum;

import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Manages the SignalR connection to the video call signaling server.
 * Handles all hub events and dispatches callbacks to the listener on the main thread.
 */
public class SignalRClient {

    private static final String TAG = "SignalRClient";

    private HubConnection hubConnection;
    private final SignalRListener listener;
    private final Handler mainHandler;
    private String currentRoomId;
    private String currentUserName;

    /**
     * Callback interface for SignalR events.
     * All methods are invoked on the main (UI) thread.
     */
    public interface SignalRListener {
        void onConnected();
        void onDisconnected();
        void onUserJoined(String connectionId, String userName, boolean isInitiator);
        void onUserLeft(String connectionId, String userName);
        void onOfferReceived(String connectionId, String sdp);
        void onAnswerReceived(String connectionId, String sdp);
        void onIceCandidateReceived(String connectionId, String candidateJson);
        void onRoomFull();
        void onError(String error);
    }

    /**
     * Creates a new SignalRClient.
     *
     * @param serverUrl The base URL of the SignalR server (e.g., "http://10.0.2.2:5000")
     * @param listener  The listener to receive signaling events
     */
    public SignalRClient(String serverUrl, SignalRListener listener) {
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        String hubUrl = serverUrl.endsWith("/") ? serverUrl + "videocallhub" : serverUrl + "/videocallhub";

        hubConnection = HubConnectionBuilder.create(hubUrl)
                .withTransport(TransportEnum.WEBSOCKETS)
                .withHandshakeResponseTimeout(30 * 1000)
                .shouldSkipNegotiate(true)
                .build();

        registerEventHandlers();
    }

    /**
     * Registers all hub event handlers.
     */
    private void registerEventHandlers() {
        // Called when another user joins the room
        hubConnection.on("UserJoined", (connectionId, userName, isInitiator) -> {
            Log.d(TAG, "UserJoined: " + userName + " (" + connectionId + ") Initiator: " + isInitiator);
            mainHandler.post(() -> listener.onUserJoined(connectionId, userName, isInitiator));
        }, String.class, String.class, Boolean.class);

        // Called when another user leaves the room
        hubConnection.on("UserLeft", (connectionId, userName) -> {
            Log.d(TAG, "UserLeft: " + userName + " (" + connectionId + ")");
            mainHandler.post(() -> listener.onUserLeft(connectionId, userName));
        }, String.class, String.class);

        // Called when an SDP offer is received from a peer
        hubConnection.on("ReceiveOffer", (connectionId, sdp) -> {
            Log.d(TAG, "ReceiveOffer from: " + connectionId);
            mainHandler.post(() -> listener.onOfferReceived(connectionId, sdp));
        }, String.class, String.class);

        // Called when an SDP answer is received from a peer
        hubConnection.on("ReceiveAnswer", (connectionId, sdp) -> {
            Log.d(TAG, "ReceiveAnswer from: " + connectionId);
            mainHandler.post(() -> listener.onAnswerReceived(connectionId, sdp));
        }, String.class, String.class);

        // Called when an ICE candidate is received from a peer
        hubConnection.on("ReceiveIceCandidate", (connectionId, candidateJson) -> {
            Log.d(TAG, "ReceiveIceCandidate from: " + connectionId);
            mainHandler.post(() -> listener.onIceCandidateReceived(connectionId, candidateJson));
        }, String.class, String.class);

        // Called when the room is full
        hubConnection.on("RoomFull", () -> {
            Log.d(TAG, "Room is full");
            mainHandler.post(listener::onRoomFull);
        });

        // Called when the server sends an error message
        hubConnection.on("Error", (error) -> {
            Log.e(TAG, "Server error: " + error);
            mainHandler.post(() -> listener.onError(error));
        }, String.class);

        // Connection lifecycle events
        hubConnection.onClosed((exception) -> {
            Log.d(TAG, "Connection closed", exception);
            mainHandler.post(listener::onDisconnected);
        });
    }

    /**
     * Starts the connection to the SignalR hub.
     */
    public void connect() {
        if (hubConnection.getConnectionState() == HubConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected");
            mainHandler.post(listener::onConnected);
            return;
        }

        Log.d(TAG, "Connecting to SignalR hub...");
        hubConnection.start()
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        // No-op
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "Connected to SignalR hub");
                        mainHandler.post(listener::onConnected);
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "Connection failed", e);
                        mainHandler.post(() -> listener.onError("Connection failed: " + e.getMessage()));
                    }
                });
    }

    /**
     * Disconnects from the SignalR hub.
     */
    public void disconnect() {
        if (hubConnection == null) return;

        if (currentRoomId != null) {
            leaveRoom(currentRoomId);
        }

        try {
            hubConnection.stop().subscribe(new CompletableObserver() {
                @Override
                public void onSubscribe(Disposable d) {
                    // No-op
                }

                @Override
                public void onComplete() {
                    Log.d(TAG, "Disconnected from SignalR hub");
                }

                @Override
                public void onError(Throwable e) {
                    Log.e(TAG, "Error disconnecting", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error during disconnect", e);
        }
    }

    /**
     * Joins a room with the specified room ID and user name.
     *
     * @param roomId   The room identifier to join
     * @param userName The display name for the user
     */
    public void joinRoom(String roomId, String userName) {
        if (hubConnection.getConnectionState() != HubConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot join room: not connected");
            mainHandler.post(() -> listener.onError("Not connected to server"));
            return;
        }

        this.currentRoomId = roomId;
        this.currentUserName = userName;

        Log.d(TAG, "Joining room: " + roomId + " as " + userName);
        hubConnection.invoke("JoinRoom", roomId, userName)
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        // No-op
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "Joined room: " + roomId);
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "Error joining room", e);
                        mainHandler.post(() -> listener.onError("Failed to join room: " + e.getMessage()));
                    }
                });
    }

    /**
     * Leaves the specified room.
     *
     * @param roomId The room identifier to leave
     */
    public void leaveRoom(String roomId) {
        if (hubConnection.getConnectionState() != HubConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot leave room: not connected");
            return;
        }

        Log.d(TAG, "Leaving room: " + roomId);
        hubConnection.invoke("LeaveRoom", roomId)
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        // No-op
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "Left room: " + roomId);
                        currentRoomId = null;
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "Error leaving room", e);
                    }
                });
    }

    /**
     * Sends an SDP offer to the target peer.
     *
     * @param targetConnectionId The SignalR connection ID of the target peer
     * @param sdp                The SDP offer string
     */
    public void sendOffer(String targetConnectionId, String sdp) {
        if (hubConnection.getConnectionState() != HubConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot send offer: not connected");
            return;
        }

        Log.d(TAG, "Sending offer to: " + targetConnectionId);
        hubConnection.invoke("SendOffer", targetConnectionId, sdp)
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        // No-op
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "Offer sent successfully");
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "Error sending offer", e);
                        mainHandler.post(() -> listener.onError("Failed to send offer: " + e.getMessage()));
                    }
                });
    }

    /**
     * Sends an SDP answer to the target peer.
     *
     * @param targetConnectionId The SignalR connection ID of the target peer
     * @param sdp                The SDP answer string
     */
    public void sendAnswer(String targetConnectionId, String sdp) {
        if (hubConnection.getConnectionState() != HubConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot send answer: not connected");
            return;
        }

        Log.d(TAG, "Sending answer to: " + targetConnectionId);
        hubConnection.invoke("SendAnswer", targetConnectionId, sdp)
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        // No-op
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "Answer sent successfully");
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "Error sending answer", e);
                        mainHandler.post(() -> listener.onError("Failed to send answer: " + e.getMessage()));
                    }
                });
    }

    /**
     * Sends an ICE candidate to the target peer.
     *
     * @param targetConnectionId The SignalR connection ID of the target peer
     * @param candidateJson      The ICE candidate as a JSON string
     */
    public void sendIceCandidate(String targetConnectionId, String candidateJson) {
        if (hubConnection.getConnectionState() != HubConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot send ICE candidate: not connected");
            return;
        }

        Log.d(TAG, "Sending ICE candidate to: " + targetConnectionId);
        hubConnection.invoke("SendIceCandidate", targetConnectionId, candidateJson)
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        // No-op
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "ICE candidate sent successfully");
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "Error sending ICE candidate", e);
                    }
                });
    }

    /**
     * Returns whether the hub connection is currently connected.
     */
    public boolean isConnected() {
        return hubConnection != null && hubConnection.getConnectionState() == HubConnectionState.CONNECTED;
    }

    /**
     * Returns the current room ID, or null if not in a room.
     */
    public String getCurrentRoomId() {
        return currentRoomId;
    }

    /**
     * Returns the connection ID assigned by SignalR.
     */
    public String getConnectionId() {
        return hubConnection != null ? hubConnection.getConnectionId() : null;
    }
}
