package cvb;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Track A: does a capture track created BEFORE a call deliver audio once the call starts?
 *
 * The whole point of Track A is that the daemon stops being a per-call dependency: if the app can hold
 * a `VOICE_CALL` track created once (while privileged) and simply start it when a call connects, then
 * no ADB and no daemon are needed at call time — permission is checked at CREATION, not at start.
 *
 * Already established on this device: creating the track with no call active succeeds, routes to
 * `AUDIO_DEVICE_IN_TELEPHONY_RX`, reports `silenced:false`, and survives indefinitely. The open
 * question is the vendor HAL: `voice_check_and_set_incall_rec_usecase()` runs once per
 * `start_input_stream()`, so a track STARTED before the call is pinned to the wrong use-case and stays
 * silent for the whole call. A track held STOPPED should get a fresh use-case evaluation when it is
 * started at call-connect — should.
 *
 * So this measures two tracks during the same call:
 *
 *   HELD    — created before the call, held stopped, started at call-connect.  <- the thing under test
 *   CONTROL — created fresh during the call, exactly as the shipped code does. <- known to work
 *
 * The control is the point. "The held track was silent" means nothing on its own — the call might have
 * had no audio, or the user might not have spoken. Silent HELD next to a loud CONTROL is a real result;
 * both silent means the run was worthless and should be repeated.
 *
 * Usage: TrackAProbe [captureSeconds]
 */
public final class TrackAProbe {

    private static final int RATE = 48000;
    private static final int CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /** VOICE_CALL == 4. Named explicitly so the probe reads the same as the shipped source. */
    private static final int SOURCE_VOICE_CALL = MediaRecorder.AudioSource.VOICE_CALL;

    private static final long POLL_MS = 500;
    private static final long WAIT_FOR_CALL_MS = 120_000;

    public static void main(String[] args) throws Exception {
        int captureSeconds = args.length > 0 ? Integer.parseInt(args[0]) : 15;

        say("=== Track A probe ===");
        say("Creating the HELD track with no call active…");

        AudioRecord held = create();
        if (held == null) {
            say("RESULT: could not create the track at all — Track A is dead here, nothing else to test.");
            return;
        }
        say("HELD created: state=" + held.getState() + " (1 = INITIALIZED), left STOPPED on purpose");

        say("Waiting for a call (up to " + (WAIT_FOR_CALL_MS / 1000) + "s) — place one now…");
        if (!waitForCall()) {
            say("RESULT: no call detected; nothing measured. Re-run and place a call.");
            held.release();
            return;
        }
        say("Call detected. Starting the HELD track — this is the use-case re-evaluation under test.");

        held.startRecording();
        double heldDb = measure(held, captureSeconds);

        say("Creating the CONTROL track mid-call (what the shipped code does today)…");
        AudioRecord control = create();
        double controlDb = -999;
        if (control != null) {
            control.startRecording();
            controlDb = measure(control, captureSeconds);
        } else {
            say("CONTROL could not be created — the comparison is missing, treat the run as inconclusive.");
        }

        say("");
        say("=== RESULT ===");
        say(String.format("HELD    (created before the call): %.1f dBFS", heldDb));
        say(String.format("CONTROL (created during the call): %.1f dBFS", controlDb));
        say("");
        if (controlDb < -70) {
            say("INCONCLUSIVE: the control is silent too, so the call itself carried no audio we could");
            say("read. Repeat with both parties speaking.");
        } else if (heldDb < -70) {
            say("NEGATIVE: the held track is digital silence while the control is not. Starting a");
            say("pre-created track at call-connect does NOT re-arm the HAL use-case. Track A cannot");
            say("remove the daemon from the call path on this device.");
        } else {
            say("POSITIVE: the held track carries real audio. A track created once, while privileged,");
            say("and merely STARTED at call-connect works — so no daemon and no ADB are needed at call");
            say("time. This is the Track A go signal.");
        }

        held.stop();
        held.release();
        if (control != null) { control.stop(); control.release(); }
    }

    private static AudioRecord create() {
        int min = AudioRecord.getMinBufferSize(RATE, CHANNELS, ENCODING);
        if (min <= 0) { say("getMinBufferSize failed: " + min); return null; }
        AudioRecord r = new AudioRecord(SOURCE_VOICE_CALL, RATE, CHANNELS, ENCODING, min * 4);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            say("AudioRecord did not initialise (state=" + r.getState() + ")");
            r.release();
            return null;
        }
        return r;
    }

    /** Peak level over the window, in dBFS. -999 means nothing was read at all. */
    private static double measure(AudioRecord r, int seconds) {
        short[] buf = new short[RATE / 10 * 2];
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int peak = 0;
        long frames = 0;
        while (System.currentTimeMillis() < deadline) {
            int n = r.read(buf, 0, buf.length);
            if (n <= 0) continue;
            frames += n;
            for (int i = 0; i < n; i++) {
                int v = Math.abs(buf[i]);
                if (v > peak) peak = v;
            }
        }
        say("  read " + frames + " samples, peak=" + peak);
        if (frames == 0) return -999;
        if (peak == 0) return -120;
        return 20 * Math.log10(peak / 32768.0);
    }

    /** Polls the audio mode owner for MODE_IN_CALL — the same discriminator the shipped code uses. */
    private static boolean waitForCall() throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_FOR_CALL_MS;
        while (System.currentTimeMillis() < deadline) {
            if (dumpsysSaysInCall()) return true;
            Thread.sleep(POLL_MS);
        }
        return false;
    }

    private static boolean dumpsysSaysInCall() {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", "dumpsys audio | grep mAudioModeOwner")
                    .redirectErrorStream(true).start();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("MODE_IN_CALL")) return true;
            }
        } catch (Exception e) {
            // Best-effort: a failed read just means "not yet".
        } finally {
            if (p != null) p.destroy();
        }
        return false;
    }

    private static void say(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
