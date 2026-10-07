package cvb;

import android.media.AudioFormat;
import android.media.AudioRecord;
import java.io.FileOutputStream;

/**
 * Track B question 1: can a shell-uid capture run while a VoIP app owns the mic?
 * Reports per-second RMS so a silenced track (all zeros) is distinguishable from a live one.
 * Usage: <sourceInt> <seconds> <outPcmPath>
 */
public class Probe {
    public static void main(String[] args) throws Exception {
        int source = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        String out = args[2];
        int rate = 48000, chan = AudioFormat.CHANNEL_IN_MONO, fmt = AudioFormat.ENCODING_PCM_16BIT;

        int min = AudioRecord.getMinBufferSize(rate, chan, fmt);
        System.out.println("PROBE src=" + source + " uid=" + android.os.Process.myUid() + " minBuf=" + min);
        if (min <= 0) { System.out.println("RESULT src=" + source + " VERDICT=BAD_BUFFER"); return; }

        AudioRecord ar = new AudioRecord(source, rate, chan, fmt, min * 4);
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            System.out.println("RESULT src=" + source + " VERDICT=CREATE_FAILED (policy denied)");
            return;
        }
        ar.startRecording();
        if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            System.out.println("RESULT src=" + source + " VERDICT=START_FAILED");
            ar.release(); return;
        }
        System.out.println("PROBE src=" + source + " capturing " + seconds + "s -> " + out);

        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[4096];
        long totalFrames = 0, nonZeroWindows = 0, windows = 0;
        long framesPerWindow = rate;                     // 1 second
        double sumSq = 0; long winFrames = 0; int winPeak = 0;
        long target = (long) rate * seconds;

        while (totalFrames < target) {
            int n = ar.read(buf, 0, buf.length);
            if (n <= 0) { System.out.println("PROBE read=" + n + " (error)"); break; }
            fos.write(buf, 0, n);
            for (int i = 0; i + 1 < n; i += 2) {
                int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                sumSq += (double) s * s; winFrames++;
                int abs = s < 0 ? -s : s; if (abs > winPeak) winPeak = abs;
            }
            totalFrames += n / 2;
            if (winFrames >= framesPerWindow) {
                double rms = Math.sqrt(sumSq / winFrames);
                double db = rms > 0 ? 20 * Math.log10(rms / 32768.0) : -999;
                System.out.println(String.format("  t=%2ds rms=%.1f peak=%d dB=%.1f%s",
                        windows, rms, winPeak, db, winPeak == 0 ? "   <-- ALL ZERO" : ""));
                if (winPeak > 0) nonZeroWindows++;
                windows++; sumSq = 0; winFrames = 0; winPeak = 0;
            }
        }
        fos.close(); ar.stop(); ar.release();
        String verdict = nonZeroWindows == 0 ? "SILENCED (all zeros)"
                : nonZeroWindows == windows ? "LIVE (audio in every window)"
                : "PARTIAL (" + nonZeroWindows + "/" + windows + " windows had audio)";
        System.out.println("RESULT src=" + source + " VERDICT=" + verdict);
    }
}
