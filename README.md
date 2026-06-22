# VideoCallSystem

This project consists of an Android application (`AndroidClient`) and a .NET backend signaling server (`Backend`).

## Structure

- **AndroidClient**: Contains the Android mobile app source code built using Gradle.
- **Backend/VideoCallSignaling**: Contains the ASP.NET Core project used for WebRTC signaling.
- **Logs**: Included are device logs which have been analyzed for crashes.

## Known Issues

Based on the analysis of the device logs, the Android application (`com.india.Phoenix`) occasionally experiences the following crashes:

1. **MainActivity ClassNotFoundException**:
   The app fails to launch because the `MainActivity` class is missing from the compiled DEX path.
   ```
   java.lang.RuntimeException: Unable to instantiate activity ComponentInfo{com.india.Phoenix/com.agatsa.sanketlife.MainActivity}: java.lang.ClassNotFoundException: Didn't find class "com.agatsa.sanketlife.MainActivity"
   ```

2. **SettingsFragment InstantiationException**:
   The `SettingsFragment` is missing an empty public constructor, causing a crash when the system tries to recreate the fragment.
   ```
   androidx.fragment.app.Fragment$InstantiationException: Unable to instantiate fragment com.india.Phoenix.activity.updates.fragments.SettingsFragment: could not find Fragment constructor
   ```

3. **LogoMain Activity NullPointerException**:
   The app crashes during the `onPause()` lifecycle of the `logo_main` activity while trying to move tasks.
   ```
   java.lang.NullPointerException: Attempt to invoke virtual method 'android.content.ComponentName android.content.Intent.getComponent()' on a null object reference
   ```

## Development

- **Android**: Open the `AndroidClient` directory in Android Studio.
- **Backend**: Open `Backend/VideoCallSignaling/VideoCallSignaling.csproj` in Visual Studio.
