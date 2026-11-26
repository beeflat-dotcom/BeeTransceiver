package jp.beeflat.beetransceiver;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Optional Bluetooth media key input for the already-armed microphone foreground
 * service. An active media session can receive headset controls with the screen
 * locked on many Android versions. This does not promise all devices will route
 * every key here. No audio is played and no audio focus is requested.
 */
final class BluetoothMediaPtt {
    private static final int[] MEDIA_KEYS = {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE
    };
    private final MediaSession session;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable onPress;
    private long lastPressAt = -10000L;
    private volatile boolean started = false;
    private int seen = 0;

    BluetoothMediaPtt(Context context, Runnable onPress) {
        this.context=context.getApplicationContext();
        this.onPress = onPress;
        session = new MediaSession(context.getApplicationContext(), "BeeBluetoothPtt");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent event) {
                KeyEvent key = event == null ? null : (KeyEvent) event.getParcelableExtra(
                        Intent.EXTRA_KEY_EVENT);
                if (key != null) ButtonInputDiagnostics.recordKey(context, "MediaSession", key);
                if (key == null) return false;
                boolean registered=HardwarePttBindings.find(context,key)>=0;
                if (!isSupported(key.getKeyCode()) && !registered) return false;
                if (key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0) {
                    pressed("key");
                }
                // Consume down, repeat, and up. Only a fresh DOWN toggles PTT.
                return true;
            }
            @Override public void onPlay() {
                ButtonInputDiagnostics.recordTransport(context,"MediaSession:onPlay");
                pressed("play");
            }
            @Override public void onPause() {
                ButtonInputDiagnostics.recordTransport(context,"MediaSession:onPause");
                pressed("pause");
            }
        }, handler);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        0.0f)
                .build());
    }

    static boolean isSupported(int keyCode) {
        for (int value : MEDIA_KEYS) if (value == keyCode) return true;
        return false;
    }

    void start() {
        if (started) return;
        started = true;
        session.setActive(true);
    }

    void stop() {
        started = false;
        session.setActive(false);
        session.release();
    }

    private void pressed(String source) {
        if (!started || ButtonInputDiagnostics.isActive(context) || MainActivity.isForeground()) return;
        long now = SystemClock.elapsedRealtime();
        // Some headsets dispatch a PLAY and MEDIA_BUTTON event for one press.
        if (now - lastPressAt < 550L) return;
        lastPressAt = now;
        seen++;
        // Counter only: do not log microphones or Bluetooth device identifiers.
        handler.post(onPress);
    }
}
