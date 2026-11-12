import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Set;

/**
 * Desktop verification of Ply's background-removal pipeline against the real
 * bundled u2netp.onnx. Builds the input exactly like BackgroundRemover does
 * (squish to trained size, RGB 0..1 CHW) and checks that the first output is a
 * sane foreground matte for a synthetic bright square on a dark background.
 */
public class TestBg {

    public static void main(String[] args) throws Exception {
        int size = 320;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession("u2netp.onnx", new OrtSession.SessionOptions())) {
            System.out.println("== model io ==");
            session.getInputInfo().forEach((name, info) -> System.out.println("  in  " + name + " -> " + info.getInfo()));
            session.getOutputInfo().forEach((name, info) -> System.out.println("  out " + name + " -> " + info.getInfo()));

            // Synthetic scene: bright square "subject" on a mid-dark background.
            int[] src = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    src[y * size + x] = 0xFF30343C;
                }
            }
            int sx0 = 90, sx1 = 230, sy0 = 80, sy1 = 250;
            for (int y = sy0; y < sy1; y++) {
                for (int x = sx0; x < sx1; x++) {
                    src[y * size + x] = 0xFFECEFF7;
                }
            }

            float[] image = new float[3 * size * size];
            for (int i = 0; i < size * size; i++) {
                int c = src[i];
                image[i] = ((c >>> 16) & 0xFF) / 255f;
                image[size * size + i] = ((c >>> 8) & 0xFF) / 255f;
                image[2 * size * size + i] = (c & 0xFF) / 255f;
            }

            String inputName = session.getInputNames().iterator().next();
            try (OnnxTensor imgT = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(image), new long[]{1, 3, size, size})) {
                long start = System.currentTimeMillis();
                try (OrtSession.Result res = session.run(Map.of(inputName, imgT))) {
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("outputs in result: " + res);
                    // First output = the d0 matte, exactly what the app reads.
                    ai.onnxruntime.OnnxValue first = res.get(0);
                    ai.onnxruntime.OnnxTensor out = (ai.onnxruntime.OnnxTensor) first;
                    System.out.println("first output shape: " + out.getInfo().getShape());
                    var fb = out.getFloatBuffer();
                    int n = size * size;
                    double inSum = 0, outSum = 0;
                    float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                    int inN = 0, outN = 0;
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            float v = fb.get(y * size + x);
                            min = Math.min(min, v);
                            max = Math.max(max, v);
                            boolean subject = x >= sx0 && x < sx1 && y >= sy0 && y < sy1;
                            if (subject) { inSum += v; inN++; } else { outSum += v; outN++; }
                        }
                    }
                    System.out.printf(
                            "inference %d ms; matte range [%.3f..%.3f]; subject mean=%.3f, background mean=%.3f%n",
                            elapsed, min, max, inSum / Math.max(1, inN), outSum / Math.max(1, outN));
                    boolean ok = inSum / Math.max(1, inN) > 0.5 && outSum / Math.max(1, outN) < 0.2;
                    System.out.println(ok ? "PASS: matte separates subject from background" : "FAIL: matte does not separate");
                }
            }
        }
    }
}
