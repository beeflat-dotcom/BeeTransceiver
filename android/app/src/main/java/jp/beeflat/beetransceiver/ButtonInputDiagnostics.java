package jp.beeflat.beetransceiver;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Explicit, time-limited troubleshooting for non-text hardware input.
 *
 * Logs up to 24 recent non-printing key presses from Activity, Accessibility,
 * or MediaSession. All results stay in this app's private preferences.
 * Does not collect text, touch events, other apps' contents or audio.
 */
final class ButtonInputDiagnostics {
    static final String ACTION_UPDATED="jp.beeflat.beetransceiver.INPUT_DIAG_UPDATED";
    private static final String PREFS="bee_transceiver";
    private static final String KEY_UNTIL="ptt_input_diag_until";
    private static final String KEY_EVENTS="ptt_input_diag_events_v1";
    private static final String KEY_LAST="ptt_input_diag_candidate_v1";
    private static final String KEY_LEARNING="ptt_learning";
    static final long DURATION_MS=90000L;
    private static final int MAX_EVENTS=24;

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    static synchronized boolean isActive(Context context) {
        return System.currentTimeMillis()<prefs(context).getLong(KEY_UNTIL,0L);
    }

    static synchronized boolean isCollecting(Context context) {
        return isActive(context)||prefs(context).getBoolean(KEY_LEARNING,false);
    }

    static synchronized void start(Context context) {
        prefs(context).edit().putLong(KEY_UNTIL,System.currentTimeMillis()+DURATION_MS)
                .remove(KEY_LAST).remove(KEY_EVENTS).apply();
        notifyChanged(context);
    }

    static synchronized void stop(Context context) {
        prefs(context).edit().putLong(KEY_UNTIL,0L).apply();
        notifyChanged(context);
    }

    static synchronized void clear(Context context) {
        prefs(context).edit().putLong(KEY_UNTIL,0L).remove(KEY_LAST)
                .remove(KEY_EVENTS).apply();
        notifyChanged(context);
    }

    static synchronized void clearCandidate(Context context) {
        prefs(context).edit().remove(KEY_LAST).apply();
        notifyChanged(context);
    }

    static synchronized boolean recordKey(Context context,String source,KeyEvent event) {
        if(!isCollecting(context)||event==null
                ||event.getAction()!=KeyEvent.ACTION_DOWN ||event.getRepeatCount()!=0)
            return false;
        if(!HardwarePttBindings.learnable(event))return false;
        int code=event.getKeyCode();
        // A user may type into another app while Accessibility is enabled.
        // Never record printable typing or software keyboard events.
        if(KeyEvent.isModifierKey(code)||code==KeyEvent.KEYCODE_ENTER
                ||code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_FORWARD_DEL
                ||code==KeyEvent.KEYCODE_TAB||code==KeyEvent.KEYCODE_SPACE)
            return false;
        if(event.getUnicodeChar()!=0)return false;

        InputDevice device=event.getDevice();
        boolean external=device!=null && device.isExternal();
        String descriptor="";
        if(external){
            String value=device.getDescriptor();
            if(value!=null)descriptor=value;
        }
        return add(context,source,code,event.getScanCode(),descriptor,
                external,false);
    }

    /**
     * Android may send only onPlay/onPause with no original KeyEvent.
     * Mark these clearly as synthetic so they cannot be mistaken for the
     * actual Bluetooth HID keycode.
     */
    static synchronized boolean recordTransport(Context context,String source) {
        if(!isCollecting(context))return false;
        return add(context,source,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0,"",false,true);
    }

    private static boolean add(Context context,String source,int code,int scan,
                               String descriptor,boolean external,boolean synthetic) {
        try {
            JSONObject item=new JSONObject();
            item.put("at",System.currentTimeMillis());
            item.put("via",source);
            item.put("code",code);
            item.put("scan",scan);
            item.put("descriptor",descriptor);
            item.put("external",external);
            item.put("synthetic",synthetic);
            PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            item.put("screen",power!=null && power.isInteractive());
            SharedPreferences store=prefs(context);
            JSONArray existing;
            try{existing=new JSONArray(store.getString(KEY_EVENTS,"[]"));}
            catch(Exception ignored){existing=new JSONArray();}
            JSONArray recent=new JSONArray();
            recent.put(item);
            for(int i=0;i<existing.length() && i<MAX_EVENTS-1;i++){
                JSONObject old=existing.optJSONObject(i);
                if(old!=null)recent.put(old);
            }
            store.edit().putString(KEY_EVENTS,recent.toString())
                    .putString(KEY_LAST,item.toString()).apply();
            notifyChanged(context);
            return true;
        }catch(Exception ignored){
            return false;
        }
    }

    static synchronized HardwarePttBindings.Binding lastCandidate(Context context) {
        String data=prefs(context).getString(KEY_LAST,"");
        if(data.isEmpty())return null;
        try {
            JSONObject item=new JSONObject(data);
            int code=item.optInt("code",-1);
            int scan=item.optInt("scan",0);
            if(code<0 || code==KeyEvent.KEYCODE_UNKNOWN && scan<=0)return null;
            return new HardwarePttBindings.Binding(code,scan,item.optString("descriptor",""));
        }catch(Exception ignored){return null;}
    }

    static synchronized String report(Context context) {
        StringBuilder out=new StringBuilder();
        if(isActive(context)) {
            long remaining=(prefs(context).getLong(KEY_UNTIL,0L)-System.currentTimeMillis()+999)/1000;
            out.append("診断中：残り約").append(Math.max(0,remaining)).append("秒（発信はしません）");
        }else {
            out.append("診断停止中");
        }
        if(!HardwarePttTalkService.isArmed()){
            out.append("\nBluetoothメディアキー受信：待機サービス停止中");
        } else {
            out.append("\nBluetoothメディアキー受信：待機サービス開始済み");
        }
        out.append("\n通常キー / メディア操作 / ユーザー補助の受信履歴：");
        JSONArray arr;
        try{arr=new JSONArray(prefs(context).getString(KEY_EVENTS,"[]"));}
        catch(Exception ignored){arr=new JSONArray();}
        if(arr.length()==0)out.append("\n検出なし。押しても増えない場合はAndroidからアプリに届いていない可能性があります。");
        for(int i=0;i<Math.min(arr.length(),12);i++){
            JSONObject item=arr.optJSONObject(i);
            if(item==null)continue;
            long at=item.optLong("at",0L);
            String time=new SimpleDateFormat("HH:mm:ss",Locale.JAPAN).format(new Date(at));
            int code=item.optInt("code",-1);
            String key=KeyEvent.keyCodeToString(code).replace("KEYCODE_","");
            out.append("\n").append(time).append(" ").append(item.optString("via","不明"))
                    .append(": ").append(key).append(" (").append(code).append(")")
                    .append(" Scan ").append(item.optInt("scan",0))
                    .append(item.optBoolean("external",false)?" 外部入力":" 内蔵/不明")
                    .append(item.optBoolean("screen",true)?" 画面ON":" 画面OFF");
            if(item.optBoolean("synthetic",false))
                out.append(" ※OSの再生/停止命令。実キー番号は不明");
        }
        return out.toString();
    }

    private static void notifyChanged(Context context) {
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
    }

    private ButtonInputDiagnostics() {}
}
