package app.turbobox;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes {@code cut_answers.json} in a run's folder. */
final class CutAnswersStore {
    private CutAnswersStore() {
    }

    private static File file(BufferStore.Run r) {
        return new File(r.dir, "cut_answers.json");
    }

    static List<CutAnswers.Window> load(BufferStore.Run r) {
        File f = file(r);
        if (!f.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            return CutAnswers.fromJson(new String(b, 0, off, "UTF-8"));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static void save(BufferStore.Run r, List<CutAnswers.Window> answers) {
        try (FileOutputStream out = new FileOutputStream(file(r))) {
            out.write(CutAnswers.toJson(answers).getBytes("UTF-8"));
        } catch (IOException | JSONException ignored) {
            // The answers just are not remembered.
        }
    }
}
