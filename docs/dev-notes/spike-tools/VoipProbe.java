package cvb;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.MediaRecorder;

/**
 * Track B downlink probe: capture the FAR PARTY of a VoIP call via a dynamic AudioPolicy loopback
 * mix, from shell uid 2000.
 *
 * Uses ROUTE_FLAG_LOOP_BACK_RENDER so the audio KEEPS PLAYING on the device — unlike REMOTE_SUBMIX,
 * which reroutes it and leaves the user unable to hear the call.
 *
 * Deliberately does NOT set allowPrivilegedPlaybackCapture: that flag is what triggers the 16 kHz
 * mono ceiling (canBeUsedForPrivilegedMediaCapture), and it is not required for the voice-comm path,
 * whose real gate is the CAPTURE_VOICE_COMMUNICATION_OUTPUT permission.
 *
 * Usage: <seconds> <sampleRate> <channels 1|2> <outPcm>
 */
public class VoipProbe {

    // Pinned from AOSP android-16.0.0_r4 (see AudioMix.java / AudioMixingRule.java).
    static final int MIX_ROLE_PLAYERS = 0;             // = AudioMix.MIX_TYPE_PLAYERS
    static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    static final int ROUTE_FLAG_RENDER = 0x1;
    static final int ROUTE_FLAG_LOOP_BACK = 0x2;
    static final int ROUTE_FLAG_LOOP_BACK_RENDER = ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER;

    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        int rate = Integer.parseInt(args[1]);
        int chCount = Integer.parseInt(args[2]);
        String out = args[3];
        // Which playback usage to match: 2 = VOICE_COMMUNICATION (VoIP), 1 = MEDIA (control test).
        int usage = args.length > 4 ? Integer.parseInt(args[4]) : AudioAttributes.USAGE_VOICE_COMMUNICATION;
        // The mix describes PLAYBACK, so it takes OUT masks; createAudioRecordSink converts them to
        // IN masks itself (inChannelMaskFromOutChannelMask). Passing IN masks here yields an
        // uninitialised AudioRecord.
        int chMask = chCount == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;

        System.out.println("VOIP-PROBE uid=" + android.os.Process.myUid()
                + " rate=" + rate + " ch=" + chCount + " usage=" + usage + " route=LOOP_BACK_RENDER");

        // NO ActivityThread.systemMain(): it sets the process attribution to packageName "android"
        // while our uid is 2000, and AudioFlinger rejects that mismatch with EX_SECURITY. Left alone,
        // the process has a trusted uid and NO package, which is exactly the case the native validator
        // (createFromTrustedUidNoPackage) accepts. A main looper is still needed for policy callbacks.
        android.os.Looper.prepareMainLooper();

        // --- AudioMixingRule: match playback tagged USAGE_VOICE_COMMUNICATION -------------------
        Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rb = ruleBuilderCls.getConstructor().newInstance();
        ruleBuilderCls.getMethod("setTargetMixRole", int.class).invoke(rb, MIX_ROLE_PLAYERS);
        AudioAttributes voiceAttrs = new AudioAttributes.Builder()
                .setUsage(usage).build();
        ruleBuilderCls.getMethod("addMixRule", int.class, Object.class)
                .invoke(rb, RULE_MATCH_ATTRIBUTE_USAGE, voiceAttrs);
        // Advisory only — AudioService overwrites it from the permission — but set it for parity
        // with the reference implementations.
        try {
            ruleBuilderCls.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(rb, true);
        } catch (NoSuchMethodException e) {
            System.out.println("  note: voiceCommunicationCaptureAllowed absent on this build");
        }
        Object rule = ruleBuilderCls.getMethod("build").invoke(rb);

        // --- AudioMix -------------------------------------------------------------------------
        Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Constructor<?> mixBuilderCtor = mixBuilderCls.getDeclaredConstructor(ruleCls);
        mixBuilderCtor.setAccessible(true);
        Object mb = mixBuilderCtor.newInstance(rule);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(chMask).build();
        mixBuilderCls.getMethod("setFormat", AudioFormat.class).invoke(mb, fmt);
        mixBuilderCls.getMethod("setRouteFlags", int.class).invoke(mb, ROUTE_FLAG_LOOP_BACK_RENDER);
        Object mix = mixBuilderCls.getMethod("build").invoke(mb);

        // --- AudioPolicy ----------------------------------------------------------------------
        Class<?> policyCls = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> ctxCls = Class.forName("android.content.Context");
        // Context may be null — AudioPolicy.getAttributionSource(null) falls back to
        // AttributionSource.myAttributionSource(), i.e. our own uid with no package.
        Object pb = policyBuilderCls.getConstructor(ctxCls).newInstance(new Object[]{null});
        policyBuilderCls.getMethod("addMix", mixCls).invoke(pb, mix);
        Object policy = policyBuilderCls.getMethod("build").invoke(pb);

        // Static registration needs no AudioManager instance and therefore no Context.
        Method register = Class.forName("android.media.AudioManager")
                .getDeclaredMethod("registerAudioPolicyStatic", policyCls);
        register.setAccessible(true);
        int rc = (Integer) register.invoke(null, policy);
        if (rc != 0) {
            System.out.println("RESULT VERDICT=REGISTER_FAILED rc=" + rc
                    + "  (missing CAPTURE_VOICE_COMMUNICATION_OUTPUT / MODIFY_AUDIO_ROUTING?)");
            return;
        }
        System.out.println("  registerAudioPolicy OK");

        // AudioPolicy.createAudioRecordSink() builds its AudioRecord with a NULL Context, so the
        // attribution falls back to the process default — which after ActivityThread.systemMain() is
        // packageName "android" while our uid is 2000. AudioFlinger rejects that mismatch with
        // EX_SECURITY ("invalid attr"). So replicate those few lines ourselves and pass a Context
        // that reports com.android.shell, matching uid 2000.
        AudioRecord record;
        try {
            record = (AudioRecord) policyCls.getMethod("createAudioRecordSink", mixCls).invoke(policy, mix);
        } catch (Exception e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            System.out.println("RESULT VERDICT=SINK_FAILED " + c);
            unregister(policyCls, policy); return;
        }
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("RESULT VERDICT=SINK_NOT_INITIALIZED");
            unregister(policyCls, policy); return;
        }

        record.startRecording();
        System.out.println("  capturing " + seconds + "s -> " + out);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[4096];
        long frames = 0, target = (long) rate * seconds * chCount;
        long windows = 0, live = 0;
        double sumSq = 0; long winSamples = 0; int winPeak = 0;
        long samplesPerWindow = (long) rate * chCount;

        while (frames < target) {
            int n = record.read(buf, 0, buf.length);
            if (n <= 0) { System.out.println("  read=" + n); break; }
            fos.write(buf, 0, n);
            for (int i = 0; i + 1 < n; i += 2) {
                int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                sumSq += (double) s * s; winSamples++;
                int a = s < 0 ? -s : s; if (a > winPeak) winPeak = a;
            }
            frames += n / 2;
            if (winSamples >= samplesPerWindow) {
                double rms = Math.sqrt(sumSq / winSamples);
                double db = rms > 0 ? 20 * Math.log10(rms / 32768.0) : -999;
                System.out.println(String.format("  t=%2ds rms=%7.1f peak=%6d dB=%6.1f %s",
                        windows, rms, winPeak, db, winPeak < 100 ? "<-- silence" : "<-- AUDIO"));
                if (winPeak >= 100) live++;
                windows++; sumSq = 0; winSamples = 0; winPeak = 0;
            }
        }
        fos.close();
        record.stop(); record.release();
        unregister(policyCls, policy);
        System.out.println("RESULT VERDICT=" + (live == 0 ? "SILENT (downlink NOT captured)"
                : live + "/" + windows + " windows had audio (DOWNLINK CAPTURED)"));
    }

    private static void unregister(Class<?> policyCls, Object policy) {
        try {
            Method u = Class.forName("android.media.AudioManager")
                    .getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyCls);
            u.setAccessible(true); u.invoke(null, policy);
            System.out.println("  policy unregistered");
        } catch (Exception e) { System.out.println("  unregister failed: " + e); }
    }

    /** A Context that reports com.android.shell so AudioFlinger's attribution check passes for uid 2000. */
    static class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
    }

    /** Replicates AudioPolicy.createAudioRecordSink, but with an explicit shell-attributed Context. */
    private static AudioRecord createSink(Object mix, Class<?> mixCls, Context sysContext,
                                          int rate, int outChMask) throws Exception {
        String regId = (String) mixCls.getMethod("getRegistration").invoke(mix);
        System.out.println("  mix registration = " + regId);

        int inMask = (Integer) AudioFormat.class
                .getMethod("inChannelMaskFromOutChannelMask", int.class)
                .invoke(null, outChMask);
        AudioFormat recFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(inMask).build();

        AudioAttributes.Builder ab = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class)
                .invoke(ab, MediaRecorder.AudioSource.REMOTE_SUBMIX);
        Method addTag = AudioAttributes.Builder.class.getMethod("addTag", String.class);
        addTag.invoke(ab, "addr=" + regId);
        addTag.invoke(ab, "fixedVolume");   // AudioRecord.SUBMIX_FIXED_VOLUME
        AudioAttributes attrs = ab.build();

        int bufSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);

        // The attribution AudioFlinger validates comes from context.getAttributionSource(), which a
        // ContextWrapper still delegates to its base. Ask for a real Context OF the shell package so
        // the package matches our uid 2000.
        Context shellCtx;
        try {
            shellCtx = sysContext.createPackageContext("com.android.shell", 0);
            System.out.println("  shell package context pkg=" + shellCtx.getPackageName()
                    + " attr=" + shellCtx.getAttributionSource());
        } catch (Exception e) {
            System.out.println("  createPackageContext failed (" + e + "), falling back to wrapper");
            shellCtx = new ShellContext(sysContext);
        }
        Constructor<?> ctor = AudioRecord.class.getDeclaredConstructor(
                AudioAttributes.class, AudioFormat.class, int.class, int.class,
                Context.class, int.class, int.class);
        ctor.setAccessible(true);
        return (AudioRecord) ctor.newInstance(attrs, recFormat, bufSize, 0, shellCtx, 0, 0);
    }
}
