# VideoCalling

A comprehensive video calling solution featuring an Android client and a .NET backend signaling server. This application facilitates real-time video communication between clients using WebRTC and ASP.NET Core SignalR.

## Features

- **Real-Time Video Calling**: Low-latency video and audio communication powered by WebRTC.
- **Signaling Server**: ASP.NET Core SignalR backend for efficient connection negotiation between peers.
- **Android App**: Native Android client offering a clean and responsive user interface for making and receiving calls.
- **Room Management**: Support for creating and joining specific video calling rooms.

## Structure

- **AndroidClient**: Contains the native Android mobile app source code built using Gradle.
- **Backend/VideoCallSignaling**: Contains the ASP.NET Core C# project that acts as the WebRTC signaling server.

## Getting Started

### Backend Server
1. Open `Backend/VideoCallSignaling/VideoCallSignaling.csproj` in Visual Studio or your preferred IDE.
2. Restore the required NuGet packages.
3. Run the project to start the signaling server locally.

### Android Client
1. Open the `AndroidClient` directory in Android Studio.
2. Sync the project with the Gradle files.
3. Update the signaling server URL in the app's networking configuration to point to your running backend.
4. Build and run the app on an Android emulator or a physical device.

## Development

- **Android**: Requires Android Studio and the Android SDK.
- **Backend**: Requires Visual Studio, JetBrains Rider, or the .NET SDK.
