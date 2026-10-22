package jp.beeflat.beetransceiver;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Authenticated WebSocket relay; new servers grant one speaker at a time.
 * The old v0.7 Windows relay is still supported until upgraded.
 */
final class InternetPttRelay {
    static final String ACTION_STATUS = "jp.beeflat.beetransceiver.PTT_INTERNET_STATUS";
    static final String ACTION_FLOOR = "jp.beeflat.beetransceiver.PTT_FLOOR_STATUS";
    static final String ACTION_TALK = "jp.beeflat.beetransceiver.PTT_TALK_RESULT";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_MINE = "mine";
    static final String EXTRA_RESULT = "result";
    static final int MODE_OFFLINE = 0, MODE_LEGACY = 1, MODE_MANAGED = 2;

    interface Receiver { void receive(byte[] packet); }
    interface SpeakerListener { void changed(String speaker, boolean active); }

    private static volatile InternetPttRelay active;
    static boolean sendActive(byte[] data) {
        InternetPttRelay relay=active;
        return relay!=null && relay.send(data);
    }
    static int requestTalk(String name) {
        InternetPttRelay relay=active;
        return relay==null ? MODE_OFFLINE : relay.requestFloor(name);
    }
    static void releaseTalk() {
        InternetPttRelay relay=active;
        if (relay!=null) relay.releaseFloor();
    }
    static String currentSpeaker() {
        InternetPttRelay relay=active;
        return relay==null ? "" : relay.speaker;
    }
    static boolean isConnected() {
        InternetPttRelay relay=active;
        return relay!=null && relay.online;
    }
    static boolean isFloorManaged() {
        InternetPttRelay relay=active;
        return relay!=null && relay.online && relay.floorSupported;
    }

    private final Context context;
    private final Receiver receiver;
    private final SpeakerListener speakerListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);
    private volatile WebSocket socket;
    private volatile boolean online = false;
    private volatile boolean floorSupported = false;
    private volatile boolean talkWanted = false;
    private volatile boolean talkGranted = false;
    private volatile String speaker = "";
    private volatile long lastFailureLog = 0;

    InternetPttRelay(Context context, Receiver receiver, SpeakerListener speakerListener) {
        this.context=context.getApplicationContext();
        this.receiver=receiver;
        this.speakerListener=speakerListener;
    }

    void start() {
        if (running.compareAndSet(false,true)) {active=this;connect();}
    }

    void reconnect() {
        if (!running.get()) { start();return; }
        generation.incrementAndGet();
        WebSocket old=socket;
        socket=null; online=false; floorSupported=false; talkWanted=false; talkGranted=false;
        connecting.set(false); handler.removeCallbacksAndMessages(null);
        changeSpeaker("",false,false);
        if(old!=null)old.cancel();
        connect();
    }

    void stop() {
        running.set(false);
        if(active==this) active=null;
        generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        WebSocket old=socket;socket=null;
        online=false;floorSupported=false;talkWanted=false;talkGranted=false;
        changeSpeaker("",false,false);
        if(old!=null)old.cancel();
        resolver.shutdownNow();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private int requestFloor(String name) {
        WebSocket ws=socket;
        if (!online || ws==null) return MODE_OFFLINE;
        if (!floorSupported) return MODE_LEGACY;
        if (talkWanted || talkGranted) return MODE_MANAGED;
        talkWanted=true;talkGranted=false;
        try {
            JSONObject request=new JSONObject().put("type","ptt_request").put("name",name);
            if(ws.send(request.toString())) return MODE_MANAGED;
        }catch(Exception ignored){}
        talkWanted=false;
        return MODE_OFFLINE;
    }

    private void releaseFloor() {
        talkWanted=false;talkGranted=false;
        WebSocket ws=socket;
        if(floorSupported && online && ws!=null) {
            try{ ws.send("{\"type\":\"ptt_release\"}"); }catch(RuntimeException ignored){}
        }
    }

    boolean send(byte[] packet) {
        WebSocket current=socket;
        if (!online || current==null || packet==null || packet.length>1450) return false;
        if (floorSupported && (!talkWanted || !talkGranted)) return false;
        if (current.queueSize()>96*1024L) return false;
        try {return current.send(ByteString.of(packet,0,packet.length));}
        catch(RuntimeException e){return false;}
    }

    boolean isOnline(){return online;}

    private void connect() {
        if(!running.get()||!connecting.compareAndSet(false,true))return;
        final int requestGeneration=generation.incrementAndGet();
        resolver.execute(()->{
            try {
                String token=UploadConfig.token(context);
                if(token.isEmpty()){
                    connecting.set(false);status("外部通話: 端末登録が必要です");scheduleRetry(15000);return;
                }
                String endpoint=EndpointDiscovery.resolve(context);
                if(!endpoint.startsWith("https://")){
                    connecting.set(false);status("外部通話: 接続先待ち");scheduleRetry(8000);return;
                }
                if(!running.get()||requestGeneration!=generation.get()){
                    connecting.set(false);return;
                }
                String url="wss://"+endpoint.substring("https://".length())+"/ptt/ws";
                Request request=new Request.Builder().url(url)
                        .header("Authorization","Bearer "+token).build();
                WebSocket newSocket=client.newWebSocket(request,new WebSocketListener(){
                    @Override public void onOpen(WebSocket ws,Response response){
                        if(!running.get()||generation.get()!=requestGeneration){
                            ws.close(1000,"stale");return;
                        }
                        socket=ws;connecting.set(false);online=true;
                        floorSupported=false;talkWanted=false;talkGranted=false;
                        BeeLog.write(context,"ptt_internet_connected","OK");
                        status("外部通話: 接続済み");
                        ws.send("{\"type\":\"hello\",\"protocol\":2}");
                    }

                    @Override public void onMessage(WebSocket ws,ByteString bytes){
                        if(online&&generation.get()==requestGeneration){
                            byte[] packet=bytes.toByteArray();
                            if(packet.length>=30&&packet.length<=1450)receiver.receive(packet);
                        }
                    }

                    @Override public void onMessage(WebSocket ws,String text){
                        if(!online||generation.get()!=requestGeneration)return;
                        try{
                            JSONObject msg=new JSONObject(text);
                            String type=msg.optString("type","");
                            switch(type){
                                case "hello_ack":
                                    if(msg.optBoolean("floor_control",false)){
                                        floorSupported=true;
                                        status("外部通話: 接続済み・発話制御あり");
                                    }
                                    break;
                                case "ptt_state":
                                    changeSpeaker(msg.optString("name",""),
                                            msg.optBoolean("active",false),
                                            msg.optBoolean("mine",false));
                                    break;
                                case "ptt_granted":
                                    if(talkWanted){
                                        talkGranted=true;
                                        talkEvent("granted",msg.optString("name",""));
                                    }else{
                                        releaseFloor();
                                    }
                                    break;
                                case "ptt_busy":
                                    talkWanted=false;talkGranted=false;
                                    talkEvent("busy",msg.optString("name",""));
                                    break;
                            }
                        }catch(Exception ex){BeeLog.write(context,"ptt_floor_parse",ex.getClass().getSimpleName());}
                    }

                    @Override public void onFailure(WebSocket ws,Throwable t,Response response){
                        disconnected(requestGeneration,"failure_"+(response==null?0:response.code()));
                    }
                    @Override public void onClosed(WebSocket ws,int code,String reason){
                        disconnected(requestGeneration,"closed_"+code);
                    }
                });
                if(!running.get()||generation.get()!=requestGeneration){
                    newSocket.cancel();connecting.set(false);
                }else{socket=newSocket;}
            }catch(Exception e){disconnected(requestGeneration,e.getClass().getSimpleName());}
        });
    }

    private void disconnected(int requestGeneration,String reason){
        if(generation.get()!=requestGeneration||!running.get())return;
        boolean wasTalking=talkWanted||talkGranted;
        talkWanted=false;talkGranted=false;
        online=false;floorSupported=false;socket=null;connecting.set(false);
        changeSpeaker("",false,false);
        if(wasTalking)talkEvent("disconnected","");
        EndpointDiscovery.invalidate(context);
        long now=System.currentTimeMillis();
        if(now-lastFailureLog>60000L){
            lastFailureLog=now;BeeLog.write(context,"ptt_internet_disconnected",reason);
        }
        status("外部通話: 再接続中");
        scheduleRetry(5000);
    }

    private void changeSpeaker(String name,boolean busy,boolean mine){
        speaker=busy?name:"";
        if(speakerListener!=null)speakerListener.changed(speaker,busy);
        context.sendBroadcast(new Intent(ACTION_FLOOR).setPackage(context.getPackageName())
                .putExtra(EXTRA_NAME,speaker).putExtra(EXTRA_ACTIVE,busy)
                .putExtra(EXTRA_MINE,mine));
    }

    private void talkEvent(String result,String name){
        context.sendBroadcast(new Intent(ACTION_TALK).setPackage(context.getPackageName())
                .putExtra(EXTRA_RESULT,result).putExtra(EXTRA_NAME,name));
    }

    private void scheduleRetry(long delayMs){
        if(running.get())handler.postDelayed(this::connect,delayMs);
    }
    private void status(String text){
        context.sendBroadcast(new Intent(ACTION_STATUS).setPackage(context.getPackageName())
                .putExtra(EXTRA_STATUS,text));
    }
}
