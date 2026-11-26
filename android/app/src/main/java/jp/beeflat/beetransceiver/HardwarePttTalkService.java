package jp.beeflat.beetransceiver;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in foreground microphone service started while MainActivity is visible.
 * A connected Android AccessibilityService forwards one of two user-learned keys.
 * The same NEC floor grant controls audio; no competing multicast path is used
 * for microphone frames originating here.
 *
 * This service never starts itself during boot or background-only operation.
 */
public final class HardwarePttTalkService extends Service {
    static final String ACTION_ARM="jp.beeflat.beetransceiver.action.HARDWARE_PTT_ARM";
    static final String ACTION_DISARM="jp.beeflat.beetransceiver.action.HARDWARE_PTT_DISARM";
    static final String ACTION_CHANGED="jp.beeflat.beetransceiver.action.HARDWARE_PTT_STATE";
    static final String EXTRA_ARMED="armed";
    static final String EXTRA_STATUS="state";
    private static final String CHANNEL="ptt_hardware_mic";
    private static final int NOTIFY_ID=2224;
    private static final int SAMPLE_RATE=16000, SAMPLES=320, AUDIO_BYTES=SAMPLES*2;
    private static final long LIMIT_MS=20000L;
    private static final AtomicBoolean CAPTURING=new AtomicBoolean(false);
    private static volatile HardwarePttTalkService active;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean armed=false;
    private volatile boolean wanting=false;
    private volatile boolean started=false;
    private volatile boolean transmissionReady=false;
    private volatile boolean usingSupport=false;
    private volatile String name="";
    private long lastKeyAt=0;
    // Deduplicate MediaSession and Activity notifications for one physical press.
    private long lastMediaPressAt=-10000L;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private PowerManager.WakeLock wakeLock;
    private final Runnable timeout=this::finish;
    private BroadcastReceiver floorReceiver;
    private BroadcastReceiver supportReadyReceiver;
    private BluetoothMediaPtt bluetoothMedia;

    static boolean isArmed(){
        HardwarePttTalkService service=active;
        return service!=null && service.armed;
    }
    static boolean isTalking(){
        HardwarePttTalkService service=active;
        return service!=null && service.wanting;
    }
    static void arm(Context context){
        Intent command=new Intent(context,HardwarePttTalkService.class).setAction(ACTION_ARM);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(command);
        else context.startService(command);
    }
    static void disarm(Context context){
        HardwarePttTalkService service=active;
        if(service!=null)service.handler.post(service::shutdown);
    }
    static void toggleFromKey(Context context){
        HardwarePttTalkService service=active;
        if(service!=null && service.armed && !MainActivity.isForeground())
            service.handler.post(()->service.toggle(false));
    }
    static void toggleFromDirectButton(Context context){
        HardwarePttTalkService service=active;
        if(service!=null && service.armed)
            service.handler.post(()->service.toggle(true));
    }

    /**
     * The foreground Activity may see the same media key as MediaSession.
     * Both paths must use the shared debounce rather than toggle twice.
     */
    static boolean mediaKeyFromActivity(Context context){
        HardwarePttTalkService service=active;
        if(service==null || !service.armed)return false;
        service.handler.post(service::handleMediaButtonPress);
        return true;
    }

    private void handleMediaButtonPress(){
        if(!armed || ButtonInputDiagnostics.isActive(this))return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastMediaPressAt<600L)return;
        lastMediaPressAt=now;
        if(wanting){
            // A background transmission can be stopped after bringing the app front.
            toggle(true);
            return;
        }
        if(MainActivity.toggleFromMediaKey())return;
        // Activity is absent/stopped: use the already-running microphone service.
        toggle(true);
    }

    @Override public void onCreate(){
        super.onCreate();
        active=this;
        createChannel();
        floorReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context, Intent intent){
                if(!wanting)return;
                String result=intent.getStringExtra(InternetPttRelay.EXTRA_RESULT);
                if("granted".equals(result)){
                    if(!started)startAudio();
                }else if("disconnected".equals(result)||"busy".equals(result)){
                    finish();
                    updateStatus("外部通信が切断されました");
                }
            }
        };
        IntentFilter filter=new IntentFilter(InternetPttRelay.ACTION_TALK);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(floorReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(floorReceiver,filter);
        supportReadyReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context, Intent intent){
                if(wanting && usingSupport)markReady();
            }
        };
        IntentFilter readyFilter=new IntentFilter(SupportRecordingService.ACTION_PTT_READY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(supportReadyReceiver,readyFilter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(supportReadyReceiver,readyFilter);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_DISARM.equals(action)){shutdown();return START_NOT_STICKY;}
        if(!ACTION_ARM.equals(action)){
            // Do not restart a microphone foreground service after a process kill.
            shutdown();
            return START_NOT_STICKY;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            shutdown();
            return START_NOT_STICKY;
        }
        try {
            Notification n=notification("物理ボタン発信待機中（録音は発信時のみ）");
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFY_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFY_ID,n);
            armed=true;
            try {
                if(bluetoothMedia==null)
                    bluetoothMedia=new BluetoothMediaPtt(this,this::handleMediaButtonPress);
                bluetoothMedia.start();
            }catch(RuntimeException e){
                BeeLog.write(this,"bt_media_ptt_start_error",e.getClass().getSimpleName());
            }
            publish("待機中（Bluetooth再生/停止ボタン対応）");
        }catch(RuntimeException error){
            BeeLog.write(this,"hardware_ptt_fg_error",error.getClass().getSimpleName());
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void toggle(boolean viaDirectLauncher){
        if(!armed)return;
        if(!viaDirectLauncher && MainActivity.isForeground())return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastKeyAt<330L)return;
        lastKeyAt=now;
        if(wanting){finish();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            updateStatus("マイク権限がありません");
            return;
        }
        // Never bypass NEC floor arbitration from the background.
        if(!InternetPttRelay.isFloorManaged()){
            updateStatus("PC音声中継の発言許可が取得できません");
            return;
        }
        name=getSharedPreferences(UploadConfig.PREFS,MODE_PRIVATE).getString("device_name","端末-"+Build.MODEL);
        int mode=InternetPttRelay.requestTalk(name);
        if(mode!=InternetPttRelay.MODE_MANAGED){
            updateStatus("外部通話に接続できません");
            return;
        }
        wanting=true;
        started=false;
        transmissionReady=false;
        usingSupport=false;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout,LIMIT_MS);
        updateStatus("順番待ち中");
    }

    private void startAudio(){
        if(!wanting || started || !armed)return;
        if(SupportRecordingService.isRunning()){
            usingSupport=true;
            started=true;
            SupportRecordingService.pttStart(this,name);
            // SupportRecordingService broadcasts READY only after it is active.
            updateStatus("支援録音を使って発信準備中");
            return;
        }
        if(!CAPTURING.compareAndSet(false,true)){
            updateStatus("マイクは使用中です");
            finish();
            return;
        }
        started=true;
        final String device=name;
        audioThread=new Thread(()->transmit(device),"BeeHardwarePttAudio");
        audioThread.start();
    }

    private void transmit(String deviceName){
        AudioRecord record=null;
        AacM4aWriter writer=null;
        AudioStorage.Target target=null;
        try {
            int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min,AUDIO_BYTES*8));
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("マイクを初期化できません");
            audioRecord=record;
            acquireWakeLock();
            target=AudioStorage.create(this,deviceName,"PTT",0);
            writer=target.openWriter();
            record.startRecording();
            if(!wanting || !armed)return;
            handler.post(this::markReady);
            UUID id=DeviceIdentity.get(this);
            byte[] label=deviceName.getBytes(StandardCharsets.UTF_8);
            while(label.length>48&&deviceName.length()>0){
                deviceName=deviceName.substring(0,deviceName.offsetByCodePoints(deviceName.length(),-1));
                label=deviceName.getBytes(StandardCharsets.UTF_8);
            }
            short[] samples=new short[SAMPLES];
            int sequence=0;
            // Avoid sending the local start cue into the transmitted microphone.
            long muteUntil=SystemClock.elapsedRealtime()+330L;
            while(CAPTURING.get() && wanting){
                int len=record.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);
                if(len<=0)continue;
                byte[] pcm=new byte[len*2];
                ByteBuffer little=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<len;i++)little.putShort(samples[i]);
                writer.writePcm(pcm,pcm.length);
                if(SystemClock.elapsedRealtime()<muteUntil)continue;
                ByteBuffer packet=ByteBuffer.allocate(4+1+16+4+1+label.length+2+pcm.length)
                        .order(ByteOrder.BIG_ENDIAN);
                packet.put(new byte[]{'B','P','T','1'}).put((byte)1)
                        .putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits())
                        .putInt(sequence++).put((byte)label.length).put(label)
                        .putShort((short)pcm.length).put(pcm);
                if(!InternetPttRelay.sendActive(packet.array()))break;
            }
        }catch(Exception e){
            BeeLog.write(this,"hardware_ptt_send_error",e.getClass().getSimpleName());
        }finally {
            CAPTURING.set(false);
            if(record!=null){
                try{record.stop();}catch(Exception ignored){}
                try{record.release();}catch(Exception ignored){}
            }
            audioRecord=null;
            if(writer!=null){
                try{
                    writer.close();
                    if(target!=null){AudioUploader.enqueue(this,target.finish());target=null;}
                }catch(Exception e){
                    BeeLog.write(this,"hardware_ptt_store_error",e.getClass().getSimpleName());
                    if(target!=null)target.abort();
                }
            }else if(target!=null)target.abort();
            releaseWakeLock();
            handler.post(()->{
                // If microphone recording ended unexpectedly, release the floor.
                if(wanting)finish();
            });
        }
    }

    private void markReady(){
        if(!wanting || !armed || transmissionReady)return;
        transmissionReady=true;
        PttStartCue.play(this);
        publish("発信中");
    }

    private void finish(){
        if(!wanting)return;
        boolean wasStarted=transmissionReady;
        boolean wasUsingSupport=usingSupport;
        wanting=false;
        started=false;
        transmissionReady=false;
        usingSupport=false;
        handler.removeCallbacks(timeout);
        CAPTURING.set(false);
        if(wasUsingSupport)SupportRecordingService.pttStop(this);
        InternetPttRelay.releaseTalk();
        if(wasStarted)PttStartCue.playEnd(this);
        publish("待機中");
    }

    private void updateStatus(String message){
        publish(message);
        BeeLog.write(this,"hardware_ptt_state",message);
    }
    private void publish(String message){
        if(!armed)return;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(
                NOTIFY_ID,notification("物理発信："+message));
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName())
                .putExtra(EXTRA_ARMED,armed).putExtra(EXTRA_STATUS,message));
    }
    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(
                    CHANNEL,"物理ボタン発信",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("バックグラウンド発信の待機と送信状態");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }
    private Notification notification(String description){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openIntent=PendingIntent.getActivity(this,1,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent=PendingIntent.getService(this,2,
                new Intent(this,HardwarePttTalkService.class).setAction(ACTION_DISARM),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("びー・トランシーバー：物理ボタン待機中")
                .setContentText(description).setContentIntent(openIntent).setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,"待機終了",stopIntent).build())
                .build();
    }
    private void acquireWakeLock(){
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "BeeTransceiver:HardwarePttAudio");
        wakeLock.acquire(LIMIT_MS+5000L);
    }
    private void releaseWakeLock(){
        if(wakeLock!=null&&wakeLock.isHeld()){
            try{wakeLock.release();}catch(RuntimeException ignored){}
        }
        wakeLock=null;
    }
    private void shutdown(){
        finish();
        armed=false;
        if(bluetoothMedia!=null){
            bluetoothMedia.stop();
            bluetoothMedia=null;
        }
        active=null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName())
                .putExtra(EXTRA_ARMED,false).putExtra(EXTRA_STATUS,"停止中"));
    }
    @Override public void onDestroy(){
        finish();
        armed=false;
        if(bluetoothMedia!=null){
            bluetoothMedia.stop();
            bluetoothMedia=null;
        }
        active=null;
        handler.removeCallbacksAndMessages(null);
        if(floorReceiver!=null)try{unregisterReceiver(floorReceiver);}catch(Exception ignored){}
        if(supportReadyReceiver!=null)try{unregisterReceiver(supportReadyReceiver);}catch(Exception ignored){}
        releaseWakeLock();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
