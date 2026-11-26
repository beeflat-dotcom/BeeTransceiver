package jp.beeflat.beetransceiver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * A deliberately tiny launcher target for OEM programmable side buttons.
 *
 * KYG01's Custom Button settings can open a selected application instead of
 * delivering an ordinary KeyEvent when the screen is asleep. This launcher
 * entry gives the user a dedicated application target to select in the OEM
 * settings. Actual mic operation is delegated to the existing user-armed
 * foreground PTT service, and only after its NEC server grants the floor.
 *
 * This cannot override restrictions imposed by the phone firmware or lockscreen.
 */
public final class DirectButtonPttActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handleLaunch();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunch();
    }

    private void handleLaunch() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = pm != null && pm.isInteractive();
        getSharedPreferences("bee_transceiver", MODE_PRIVATE).edit()
                .putLong("ptt_last_direct_launch_at", System.currentTimeMillis())
                .putBoolean("ptt_last_direct_launch_interactive", interactive)
                .apply();

        if (!HardwarePttTalkService.isArmed()) {
            // Do not silently activate the microphone on a locked phone.
            Toast.makeText(this, "トランシーバーのバックグラウンド待機を先に開始してください",
                    Toast.LENGTH_SHORT).show();
        } else {
            HardwarePttTalkService.toggleFromDirectButton(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
