package cvref;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.util.Log;

// Minimal shell-uid Java AudioRecord reference: the SAME path the working CallVault daemon uses.
// Its native AudioRecord::set() logs "set(): inputSource... attributionSource..." — the ground truth
// to diff against the raw-native probe. Run via: CLASSPATH=cvref.jar app_process /data/local/tmp cvref.Ref
public class Ref {
    static final String T = "CVRef";
    public static void main(String[] a) {
        int rate = 16000, chan = AudioFormat.CHANNEL_IN_MONO, fmt = AudioFormat.ENCODING_PCM_16BIT;
        Log.i(T, "=== CVRef start uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid() + " ===");
        try {
            int min = AudioRecord.getMinBufferSize(rate, chan, fmt);
            Log.i(T, "getMinBufferSize=" + min);
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, chan, fmt, Math.max(min, 4096));
            Log.i(T, "AudioRecord.getState=" + ar.getState() + " (1=INITIALIZED)");
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) { Log.e(T, "NOT INITIALIZED -> createRecord failed"); return; }
            ar.startRecording();
            Log.i(T, "recordingState=" + ar.getRecordingState() + " (3=RECORDING)");
            byte[] buf = new byte[1280]; int total = 0, peak = 0;
            for (int i = 0; i < 30; i++) {
                int n = ar.read(buf, 0, buf.length);
                if (n > 0) { total += n; for (int k = 0; k + 1 < n; k += 2) { int s = (short)((buf[k] & 0xff) | (buf[k+1] << 8)); int ab = Math.abs(s); if (ab > peak) peak = ab; } }
            }
            Log.i(T, "CAPTURE total=" + total + " peak=" + peak + (peak > 0 ? " REAL" : " SILENT"));
            ar.stop(); ar.release();
        } catch (Throwable t) { Log.e(T, "FAILED: " + t, t); }
        Log.i(T, "=== CVRef done ===");
    }
}
