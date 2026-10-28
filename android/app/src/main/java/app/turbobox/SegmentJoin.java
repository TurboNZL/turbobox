package app.turbobox;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Joins a recording's 30-second pieces, in order, into one MP4 without re-encoding: every piece's samples are copied with their times
 * moved on by the length of the pieces before. The pieces are all made by the same encoders, so their formats match.
 */
final class SegmentJoin {
    interface Progress {
        void at(int piece, int total);
    }

    private SegmentJoin() {
    }

    static void join(List<BufferStore.Segment> segs, File dest, Progress progress) throws IOException {
        if (segs.isEmpty()) throw new IOException("nothing to join");
        MediaMuxer mux = null;
        boolean started = false;
        try {
            int vOut = -1, aOut = -1;
            long offsetUs = 0;
            ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < segs.size(); i++) {
                progress.at(i + 1, segs.size());
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(segs.get(i).file.getAbsolutePath());
                    int vIn = -1, aIn = -1;
                    MediaFormat vf = null, af = null;
                    for (int t = 0; t < ex.getTrackCount(); t++) {
                        MediaFormat f = ex.getTrackFormat(t);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime == null) continue;
                        if (mime.startsWith("video/") && vIn < 0) {
                            vIn = t;
                            vf = f;
                        } else if (mime.startsWith("audio/") && aIn < 0) {
                            aIn = t;
                            af = f;
                        }
                    }
                    if (mux == null) {
                        mux = new MediaMuxer(dest.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        if (vf != null) vOut = mux.addTrack(vf);
                        if (af != null) aOut = mux.addTrack(af);
                        mux.start();
                        started = true;
                    }
                    if (vIn >= 0) ex.selectTrack(vIn);
                    if (aIn >= 0) ex.selectTrack(aIn);
                    long baseV = -1, baseA = -1, endUs = 0;
                    while (true) {
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        int track = ex.getSampleTrackIndex();
                        long t = ex.getSampleTime();
                        boolean video = track == vIn;
                        int out = video ? vOut : aOut;
                        if (out >= 0) {
                            if (video) {
                                if (baseV < 0) baseV = t;
                                t -= baseV;
                            } else {
                                if (baseA < 0) baseA = t;
                                t -= baseA;
                            }
                            info.set(0, n, offsetUs + t, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            mux.writeSampleData(out, buf, info);
                            endUs = Math.max(endUs, t);
                        }
                        ex.advance();
                    }
                    // The next piece starts where this one ended (a piece's own length when it has no samples).
                    offsetUs += endUs > 0 ? endUs + 33_000 : segs.get(i).durationMs * 1000L;
                } finally {
                    ex.release();
                }
            }
        } finally {
            if (mux != null) {
                try {
                    if (started) mux.stop();
                } catch (RuntimeException ignored) {
                    // Nothing was written.
                }
                mux.release();
            }
        }
    }
}
