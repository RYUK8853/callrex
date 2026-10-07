package cvb;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Track B uplink hunt. The downlink policy tap is proven; the near-side mic gets zero-filled during a
 * call because a record client that cannot bypass the in-call policy is silenced:
 *   !(isInCall && !canCaptureCall) && !(isInCommunication && !canCaptureCommunication)
 * WhatsApp puts the device in MODE_IN_COMMUNICATION, so plain mic capture is muted.
 *
 * This runs the DOWNLINK continuously as a live control (proving the call is up and audio is flowing)
 * while trying uplink variants in sequence. Whichever variant returns non-silence is the answer.
 *
 * Usage: <secondsPerVariant> <dlPcm>
 */
public class UplinkProbe {
    static final int MIX_ROLE_PLAYERS = 0, RULE_USAGE = 0x1, LOOP_BACK_RENDER = 0x3, RATE = 48000;
    static final int SRC_MIC = 1, SRC_VOICE_COMM = 7;
    static volatile boolean dlRun = true;

    public static void main(String[] args) throws Exception {
        int per = Integer.parseInt(args[0]);
        String dlPath = args[1];
        android.os.Looper.prepareMainLooper();
        System.out.println("UPLINK-PROBE uid=" + android.os.Process.myUid() + " " + per + "s per variant");

        Object[] reg = registerDownlink();
        if (reg == null) return;
        Object policy = reg[0]; Class<?> policyCls = (Class<?>) reg[1]; AudioRecord dl = (AudioRecord) reg[2];

        dl.startRecording();
        Thread dlT = new Thread(() -> drain(dl, dlPath, "DL"));
        dlT.setDaemon(true); dlT.start();
        System.out.println("  downlink control running — PLACE/CONTINUE THE CALL AND KEEP TALKING\n");
        Thread.sleep(6000);   // lead-in so the call is up before variants start

        List<String> results = new ArrayList<>();
        results.add(tryVariant("1 VOICE_COMM plain",           () -> plain(SRC_VOICE_COMM), per));
        results.add(tryVariant("2 MIC plain",                  () -> plain(SRC_MIC), per));
        results.add(tryVariant("3 VOICE_COMM +callRedirection",() -> withAttrs(SRC_VOICE_COMM, true, false), per));
        results.add(tryVariant("4 MIC +callRedirection",       () -> withAttrs(SRC_MIC, true, false), per));
        results.add(tryVariant("5 VOICE_COMM +shellPackage",   () -> withAttrs(SRC_VOICE_COMM, false, true), per));

        dlRun = false; Thread.sleep(300);
        dl.stop(); dl.release();
        unregister(policyCls, policy);
        System.out.println("\n================ UPLINK RESULTS ================");
        for (String r : results) System.out.println("  " + r);
        System.out.println("  (downlink ran throughout as the live control)");
    }

    interface Maker { AudioRecord make() throws Exception; }

    private static String tryVariant(String name, Maker maker, int seconds) {
        System.out.println("--- " + name + " ---");
        AudioRecord ar = null;
        try {
            ar = maker.make();
            if (ar == null || ar.getState() != AudioRecord.STATE_INITIALIZED)
                return name + " -> CREATE FAILED";
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                return name + " -> START FAILED";
            byte[] buf = new byte[4096];
            long frames = 0, target = (long) RATE * seconds;
            double sumSq = 0; long n = 0; int peak = 0;
            while (frames < target) {
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) break;
                for (int i = 0; i + 1 < r; i += 2) {
                    int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    sumSq += (double) s * s; n++;
                    int a = s < 0 ? -s : s; if (a > peak) peak = a;
                }
                frames += r / 2;
            }
            double rms = n > 0 ? Math.sqrt(sumSq / n) : 0;
            System.out.println(String.format("    rms=%.1f peak=%d", rms, peak));
            return name + String.format(" -> %s (rms=%.1f peak=%d)",
                    peak < 100 ? "SILENCED" : "*** AUDIO ***", rms, peak);
        } catch (Throwable t) {
            Throwable c = t.getCause() == null ? t : t.getCause();
            return name + " -> EXCEPTION " + c;
        } finally {
            if (ar != null) { try { ar.stop(); ar.release(); } catch (Exception ignored) { } }
        }
    }

    private static AudioRecord plain(int source) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
    }

    /** Builds via AudioAttributes so we can request call-redirection, and optionally a packaged Context. */
    private static AudioRecord withAttrs(int source, boolean callRedirection, boolean shellPackage) throws Exception {
        AudioAttributes.Builder ab = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class).invoke(ab, source);
        if (callRedirection) {
            // Shell holds CALL_AUDIO_INTERCEPTION; this is the sanctioned call-audio interception route.
            AudioAttributes.Builder.class.getMethod("setForCallRedirection").invoke(ab);
        }
        AudioAttributes attrs = ab.build();
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (!shellPackage) {
            // public at runtime, hidden in the SDK stub
            Constructor<?> c4 = AudioRecord.class.getConstructor(AudioAttributes.class,
                    AudioFormat.class, int.class, int.class);
            return (AudioRecord) c4.newInstance(attrs, fmt, min * 4, 0);
        }

        // The in-call exemption may be resolved per attribution PACKAGE, and this process deliberately
        // has none (that is what makes the downlink work). Give the uplink a shell-package identity.
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Object at = atCls.getMethod("systemMain").invoke(null);
        Object sysCtx = atCls.getMethod("getSystemContext").invoke(at);
        Object shellCtx = sysCtx.getClass().getMethod("createPackageContext", String.class, int.class)
                .invoke(sysCtx, "com.android.shell", 0);
        Constructor<?> ctor = AudioRecord.class.getDeclaredConstructor(AudioAttributes.class,
                AudioFormat.class, int.class, int.class, Class.forName("android.content.Context"),
                int.class, int.class);
        ctor.setAccessible(true);
        return (AudioRecord) ctor.newInstance(attrs, fmt, min * 4, 0, shellCtx, 0, 0);
    }

    private static void drain(AudioRecord ar, String path, String tag) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            byte[] buf = new byte[4096];
            double sumSq = 0; long n = 0; int peak = 0, win = 0;
            while (dlRun) {
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) break;
                fos.write(buf, 0, r);
                for (int i = 0; i + 1 < r; i += 2) {
                    int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    sumSq += (double) s * s; n++;
                    int a = s < 0 ? -s : s; if (a > peak) peak = a;
                }
                if (n >= RATE * 3L) {
                    System.out.println(String.format("  [%s t=%2ds rms=%.0f peak=%d %s]",
                            tag, win * 3, Math.sqrt(sumSq / n), peak, peak < 100 ? "silence" : "AUDIO"));
                    win++; sumSq = 0; n = 0; peak = 0;
                }
            }
        } catch (Exception e) { System.out.println("  " + tag + " error " + e); }
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
        if ((Integer) reg.invoke(null, policy) != 0) { System.out.println("DOWNLINK REGISTER FAILED"); return null; }
        AudioRecord dl = (AudioRecord) policyCls.getMethod("createAudioRecordSink", mixCls).invoke(policy, mix);
        if (dl == null || dl.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("DOWNLINK SINK FAILED"); return null;
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
