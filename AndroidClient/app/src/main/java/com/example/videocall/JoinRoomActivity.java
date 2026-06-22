package com.example.videocall;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Pre-call screen where the user enters the server URL, room ID, and user name
 * before joining a video call room.
 */
public class JoinRoomActivity extends AppCompatActivity {

    private static final String TAG = "JoinRoomActivity";

    private TextInputLayout tilServerUrl;
    private TextInputLayout tilRoomId;
    private TextInputLayout tilUserName;
    private TextInputEditText etServerUrl;
    private TextInputEditText etRoomId;
    private TextInputEditText etUserName;
    private MaterialButton btnJoinRoom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_room);

        initViews();
        setupListeners();
    }

    /**
     * Initializes all UI view references.
     */
    private void initViews() {
        tilServerUrl = findViewById(R.id.til_server_url);
        tilRoomId = findViewById(R.id.til_room_id);
        tilUserName = findViewById(R.id.til_user_name);
        etServerUrl = findViewById(R.id.et_server_url);
        etRoomId = findViewById(R.id.et_room_id);
        etUserName = findViewById(R.id.et_user_name);
        btnJoinRoom = findViewById(R.id.btn_join_room);
    }

    /**
     * Sets up click listeners.
     */
    private void setupListeners() {
        btnJoinRoom.setOnClickListener(v -> attemptJoinRoom());

        // Clear errors when user starts typing
        etServerUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) tilServerUrl.setError(null);
        });

        etRoomId.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) tilRoomId.setError(null);
        });

        etUserName.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) tilUserName.setError(null);
        });
    }

    /**
     * Validates inputs and starts VideoCallActivity if all inputs are valid.
     */
    private void attemptJoinRoom() {
        // Clear previous errors
        tilServerUrl.setError(null);
        tilRoomId.setError(null);
        tilUserName.setError(null);

        String serverUrl = getTextValue(etServerUrl);
        String roomId = getTextValue(etRoomId);
        String userName = getTextValue(etUserName);

        boolean isValid = true;

        // Validate Server URL
        if (TextUtils.isEmpty(serverUrl)) {
            tilServerUrl.setError(getString(R.string.error_empty_server_url));
            isValid = false;
        }

        // Validate Room ID
        if (TextUtils.isEmpty(roomId)) {
            tilRoomId.setError(getString(R.string.error_empty_room_id));
            isValid = false;
        }

        // Validate User Name
        if (TextUtils.isEmpty(userName)) {
            tilUserName.setError(getString(R.string.error_empty_user_name));
            isValid = false;
        }

        if (!isValid) {
            return;
        }

        // Remove trailing slash from server URL if present
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        // Launch VideoCallActivity with all required parameters
        Intent intent = new Intent(this, VideoCallActivity.class);
        intent.putExtra(VideoCallActivity.EXTRA_SERVER_URL, serverUrl);
        intent.putExtra(VideoCallActivity.EXTRA_ROOM_ID, roomId);
        intent.putExtra(VideoCallActivity.EXTRA_USER_NAME, userName);
        startActivity(intent);
    }

    /**
     * Extracts and trims text from a TextInputEditText.
     *
     * @param editText The TextInputEditText to get text from
     * @return The trimmed text, or an empty string if null
     */
    private String getTextValue(TextInputEditText editText) {
        if (editText.getText() != null) {
            return editText.getText().toString().trim();
        }
        return "";
    }
}
