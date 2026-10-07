package cvb;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Track B: a complete two-sided VoIP call recording, non-root, from shell uid 2000.
 *
 *   DOWNLINK (far party) — dynamic AudioPolicy loopback mix on USAGE_VOICE_COMMUNICATION playback,
 *       ROUTE_FLAG_LOOP_BACK_RENDER so the call stays audible to the user.
 *   UPLINK   (near party) — a plain MIC AudioRecord. The source matters: VOICE_COMMUNICATION is
 *       zero-filled during a call because it contends with the VoIP app itself; MIC is not.
 *
 * Writes ONE interleaved stereo stream, LEFT = you, RIGHT = them — the same channel layout the
 * shipped VOICE_CALL path produces, so the existing encoder consumes it unchanged.
 *
 * The two streams are independent AudioRecords, so the muxer aligns them on wall-clock: it pairs one
 * chunk from each, and substitutes silence for whichever side has nothing ready. A stalled or silenced
 * side therefore costs its own channel, never the timeline.
 *
 * Usage: <seconds> <outStereoPcm>
 */
public class CallRecorder {
    static final int RATE = 48000, SRC_MIC = 1;
    static final int MIX_ROLE_PLAYERS = 0, RULE_USAGE = 0x1, LOOP_BACK_RENDER = 0x3;
    static final int CHUNK_FRAMES = 960;                 // 20 ms
    static final int CHUNK_BYTES = CHUNK_FRAMES * 2;     // mono PCM-16
    static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        String out = args[1];
        android.os.Looper.prepareMainLooper();
        System.out.println("CALL-RECORDER uid=" + android.os.Process.myUid() + " " + seconds + "s -> " + out);

        Object[] reg = registerDownlink();
        if (reg == null) return;
        Object policy = reg[0]; Class<?> policyCls = (Class<?>) reg[1]; AudioRecord dl = (AudioRecord) reg[2];

        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ul = new AudioRecord(SRC_MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4);
        if (ul.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("uplink mic FAILED"); unregister(policyCls, policy); return;
        }

        BlockingQueue<byte[]> qUl = new ArrayBlockingQueue<>(400);
        BlockingQueue<byte[]> qDl = new ArrayBlockingQueue<>(400);
        dl.startRecording(); ul.startRecording();
        System.out.println("  recording — LEFT=you  RIGHT=them");

        Thread tUl = feeder(ul, qUl), tDl = feeder(dl, qDl);
        tUl.setDaemon(true); tDl.setDaemon(true); tUl.start(); tDl.start();

        byte[] silence = new byte[CHUNK_BYTES];
        byte[] stereo = new byte[CHUNK_BYTES * 2];
        long chunks = (long) seconds * RATE / CHUNK_FRAMES;
        long ulLost = 0, dlLost = 0;
        double ulSq = 0, dlSq = 0; long n = 0; int ulPeak = 0, dlPeak = 0, win = 0;

        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            for (long c = 0; c < chunks; c++) {
                byte[] u = qUl.poll(120, TimeUnit.MILLISECONDS);
                byte[] d = qDl.poll(120, TimeUnit.MILLISECONDS);
                if (u == null) { u = silence; ulLost++; }
                if (d == null) { d = silence; dlLost++; }
                for (int i = 0, o = 0; i < CHUNK_BYTES; i += 2, o += 4) {
                    stereo[o]     = u[i];     stereo[o + 1] = u[i + 1];   // L = you
                    stereo[o + 2] = d[i];     stereo[o + 3] = d[i + 1];   // R = them
                    int su = (short) ((u[i] & 0xFF) | (u[i + 1] << 8));
                    int sd = (short) ((d[i] & 0xFF) | (d[i + 1] << 8));
                    ulSq += (double) su * su; dlSq += (double) sd * sd; n++;
                    int a = su < 0 ? -su : su; if (a > ulPeak) ulPeak = a;
                    int b = sd < 0 ? -sd : sd; if (b > dlPeak) dlPeak = b;
                }
                os.write(stereo);
                if (n >= RATE) {
                    System.out.println(String.format("  t=%2ds  YOU rms=%6.0f peak=%6d   THEM rms=%6.0f peak=%6d",
                            win, Math.sqrt(ulSq / n), ulPeak, Math.sqrt(dlSq / n), dlPeak));
                    win++; ulSq = 0; dlSq = 0; n = 0; ulPeak = 0; dlPeak = 0;
                }
            }
        }
        running = false;
        dl.stop(); dl.release(); ul.stop(); ul.release();
        unregister(policyCls, policy);
        System.out.println("  chunks substituted with silence: uplink=" + ulLost + " downlink=" + dlLost
                + " of " + chunks);
        System.out.println("RESULT stereo PCM written: " + out);
    }

    private static Thread feeder(AudioRecord ar, BlockingQueue<byte[]> q) {
        return new Thread(() -> {
            byte[] buf = new byte[CHUNK_BYTES];
            while (running) {
                int off = 0;
                while (off < CHUNK_BYTES && running) {
                    int r = ar.read(buf, off, CHUNK_BYTES - off);
                    if (r <= 0) return;
                    off += r;
                }
                q.offer(buf.clone());   // drop rather than block if the muxer falls behind
            }
        });
    }

    private static Object[] registerDownlink() throws Exception {
        Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rb = ruleB.getConstructor().newInstance();
        ruleB.getMethod("setTargetMixRole", int.class).invoke(rb, MIX_ROLE_PLAYERS);
        ruleB.getMethod("addMixRule", int.class, Object.class).invoke(rb, RULE_USAGE,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build());
        try { ruleB.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(rb, true); }
        catch (NoSuchMethodException ignored) { }
        Object rule = ruleB.getMethod("build").invoke(rb);

        Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixB = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Constructor<?> mc = mixB.getDeclaredConstructor(ruleCls); mc.setAccessible(true);
        Object mb = mc.newInstance(rule);
        mixB.getMethod("setFormat", AudioFormat.class).invoke(mb, new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build());
        mixB.getMethod("setRouteFlags", int.class).invoke(mb, LOOP_BACK_RENDER);
        Object mix = mixB.getMethod("build").invoke(mb);

        Class<?> policyCls = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyB = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object pb = policyB.getConstructor(Class.forName("android.content.Context")).newInstance(new Object[]{null});
        policyB.getMethod("addMix", mixCls).invoke(pb, mix);
        Object policy = policyB.getMethod("build").invoke(pb);
        Method reg = Class.forName("android.media.AudioManager")
                .getDeclaredMethod("registerAudioPolicyStatic", policyCls);
        reg.setAccessible(true);
        if ((Integer) reg.invoke(null, policy) != 0) { System.out.println("downlink register FAILED"); return null; }
        AudioRecord dl = (AudioRecord) policyCls.getMethod("createAudioRecordSink", mixCls).invoke(policy, mix);
        if (dl == null || dl.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("downlink sink FAILED"); return null;
        }
        return new Object[]{policy, policyCls, dl};
    }

    private static void unregister(Class<?> policyCls, Object policy) {
        try {
            Method u = Class.forName("android.media.AudioManager")
                    .getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyCls);
            u.setAccessible(true); u.invoke(null, policy);
        } catch (Exception ignored) { }
    }
}
