package jp.beeflat.beetransceiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Two independent user-learned, persistent key bindings for the existing PTT toggle.
 * Only hardware key events actually delivered to the foreground Activity can be learnt.
 * Never intercept Android's navigation, power or system lock keys.
 */
final class HardwarePttBindings {
    static final int SLOTS = 2;
    private static final String PREFS = "bee_transceiver";
    private static final String PREFIX = "physical_ptt_button_";

    static final class Binding {
        final int keyCode;
        final int scanCode;
        final String deviceDescriptor;

        Binding(int keyCode, int scanCode, String deviceDescriptor) {
            this.keyCode = keyCode;
            this.scanCode = scanCode;
            this.deviceDescriptor = deviceDescriptor == null ? "" : deviceDescriptor;
        }

        boolean matches(KeyEvent event) {
            if (event == null || event.getKeyCode() != keyCode) return false;
            if (scanCode > 0 && event.getScanCode() > 0 && scanCode != event.getScanCode())
                return false;
            if (!deviceDescriptor.isEmpty()) {
                InputDevice device = event.getDevice();
                if (device != null) {
                    String descriptor = device.getDescriptor();
                    if (descriptor != null && !descriptor.isEmpty()
                            && !deviceDescriptor.equals(descriptor)) return false;
                }
            }
            return true;
        }
    }

    private static String key(int slot, String suffix) {
        if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("invalid slot");
        return PREFIX + slot + "_" + suffix;
    }

    static boolean learnable(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() != 0) return false;
        if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false;
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_ENDCALL:
                return false;
            default:
                return keyCode != KeyEvent.KEYCODE_UNKNOWN || event.getScanCode() > 0;
        }
    }

    static Binding fromEvent(KeyEvent event) {
        if (!learnable(event)) return null;
        InputDevice device = event.getDevice();
        String descriptor = "";
        if (device != null && device.isExternal()) {
            String id = device.getDescriptor();
            if (id != null) descriptor = id;
        }
        return new Binding(event.getKeyCode(), event.getScanCode(), descriptor);
    }

    static Binding load(Context context, int slot) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int code = prefs.getInt(key(slot, "code"), -1);
        if (code == -1) return null;
        return new Binding(code, prefs.getInt(key(slot, "scan"), 0),
                prefs.getString(key(slot, "device"), ""));
    }

    static boolean conflicts(Binding a, Binding b) {
        if (a == null || b == null || a.keyCode != b.keyCode) return false;
        // If scan codes differ, the physical buttons can be distinguished by scan code.
        if (a.scanCode > 0 && b.scanCode > 0 && a.scanCode != b.scanCode)
            return false;
        // On two different external devices the same key can be bound independently.
        return a.deviceDescriptor.isEmpty() || b.deviceDescriptor.isEmpty()
                || a.deviceDescriptor.equals(b.deviceDescriptor);
    }

    static boolean save(Context context, int slot, Binding binding) {
        if (binding == null) return false;
        for (int i = 0; i < SLOTS; i++) {
            if (i != slot && conflicts(binding, load(context, i))) return false;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(key(slot, "code"), binding.keyCode)
                .putInt(key(slot, "scan"), binding.scanCode)
                .putString(key(slot, "device"), binding.deviceDescriptor)
                .apply();
        return true;
    }

    static void clear(Context context, int slot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(key(slot, "code"))
                .remove(key(slot, "scan"))
                .remove(key(slot, "device"))
                .apply();
    }

    static int find(Context context, KeyEvent event) {
        for (int i = 0; i < SLOTS; i++) {
            Binding binding = load(context, i);
            if (binding != null && binding.matches(event)) return i;
        }
        return -1;
    }

    static String describe(Binding binding) {
        if (binding == null) return "未登録";
        String label;
        switch (binding.keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                label = "音量＋";
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                label = "音量－";
                break;
            case KeyEvent.KEYCODE_HEADSEThOOK:
                label = "ヘッドセットボタン";
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                label = "メディア再生／停止";
                break;
            default:
                label = KeyEvent.keyCodeToString(binding.keyCode).replace("KEYCODE_", "");
                if (binding.keyCode == KeyEvent.KEYCODE_UNKNOWN)
                    label = "特殊ボタン";
        }
        return label + (binding.scanCode > 0 ? "（Scan " + binding.scanCode + "）" : "");
    }

    private HardwarePttBindings() {}
}
