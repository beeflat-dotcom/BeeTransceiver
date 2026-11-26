package jp.beeflat.beetransceiver;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional, explicitly enabled service to capture only the two registered PTT keys.
 * Does not inspect UI events or screen content and does not consume other keys.
 */
public final class HardwarePttAccessibilityService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || !HardwarePttTalkService.isArmed()) return false;
        if (getSharedPreferences("bee_transceiver", MODE_PRIVATE).getBoolean("ptt_learning", false))
            return false;
        int slot = HardwarePttBindings.find(this, event);
        if (slot < 0) return false;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
            HardwarePttTalkService.toggleFromKey(this);
        // Consume both down and up to avoid turning volume/media actions on accidentally.
        return action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // We deliberately do not request or inspect accessibility events.
    }
    @Override public void onInterrupt() {}
}
