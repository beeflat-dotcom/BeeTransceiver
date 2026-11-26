package jp.beeflat.beetransceiver;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** Confirmation only after the microphone is ready and the floor is granted. */
final class PttStartCue {
    private static final Handler HANDLER=new Handler(Looper.getMainLooper());
    private static final int VOLUME_PERCENT=100;
    private static final int TONE_DURATION_MS=260;

    static void play(Context context) {
        HANDLER.post(()->{
            ToneGenerator tone=null;
            try {
                tone=new ToneGenerator(AudioManager.STREAM_MUSIC,VOLUME_PERCENT);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP,TONE_DURATION_MS);
                final ToneGenerator playing=tone;
                HANDLER.postDelayed(playing::release,TONE_DURATION_MS+180L);
            }catch(RuntimeException ignored){
                if(tone!=null)tone.release();
            }
        });
    }
    /** Distinct double confirmation when an actual transmission ends. */
    static void playEnd(Context context) {
        HANDLER.post(()->{
            ToneGenerator tone=null;
            try {
                tone=new ToneGenerator(AudioManager.STREAM_MUSIC,VOLUME_PERCENT);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2,230);
                final ToneGenerator playing=tone;
                HANDLER.postDelayed(playing::release,430L);
            }catch(RuntimeException ignored){
                if(tone!=null)tone.release();
            }
        });
    }
    private PttStartCue() {}
}
