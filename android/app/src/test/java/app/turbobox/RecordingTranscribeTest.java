package app.turbobox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RecordingTranscribeTest {
    @Test
    public void stereoAt48kBecomesMonoAt24k() {
        byte[] stereo = new byte[48000 * 2 * 2];   // one second of stereo 16-bit silence at 48 kHz
        assertEquals(24000 * 2, RecordingTranscribe.convert(stereo, 48000, 2).length);
    }

    @Test
    public void monoAt24kIsLeftAlone() {
        byte[] mono = new byte[2400 * 2];
        mono[0] = 5;
        byte[] out = RecordingTranscribe.convert(mono, 24000, 1);
        assertEquals(mono.length, out.length);
        assertEquals(5, out[0]);
    }
}
