import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Desktop reproduction of Ply's LamaInpainter tensor pipeline, run against the real
 * LaMa_dynamic.onnx. Builds tensors from packed ARGB ints exactly like the app does
 * (CHW, image /255, mask 1=hole, aligned size, reflect padding) and reports whether
 * the hole actually changes. Also probes convention variants in case the documented
 * contract differs from the graph's behavior.
 */
public class TestLama {

    static int reflect(int coordinate, int size) {
        if (size == 1) return 0;
        int p = coordinate % (2 * (size - 1));
        return p < size ? p : 2 * (size - 1) - p;
    }

    static int bitmapWidth;

    public static void main(String[] args) throws Exception {
        int w = 128, h = 128; // already aligned to 8
        bitmapWidth = w;
        int[] src = new int[w * h];
        int[] msk = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / (w - 1);
                int g = y * 255 / (h - 1);
                src[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | 128;
            }
        }
        int hx0 = 40, hx1 = 88, hy0 = 40, hy1 = 88; // 48x48 hole
        for (int y = hy0; y < hy1; y++) {
            for (int x = hx0; x < hx1; x++) {
                src[y * w + x] = 0xFFFFFFFF; // the "object"
                msk[y * w + x] = 0xFFFF3B30; // painted red
            }
        }

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession("LaMa_dynamic.onnx", new OrtSession.SessionOptions())) {
            System.out.println("== model io ==");
            session.getInputInfo().forEach((name, info) -> System.out.println("  in  " + name + " -> " + info.getInfo()));
            session.getOutputInfo().forEach((name, info) -> System.out.println("  out " + name + " -> " + info.getInfo()));

            String[][] variants = {
                    // name, mask true-value (1 or 255), invert
                    {"normal 0/1", "1", "false"},
                    {"inverted 0/1", "1", "true"},
                    {"normal 0/255", "255", "false"},
            };
            for (String[] variant : variants) {
                float holeValue = Float.parseFloat(variant[1]);
                boolean invert = Boolean.parseBoolean(variant[2]);
                float[] image = new float[3 * w * h];
                float[] mask = new float[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x;
                        int color = src[i];
                        image[i] = ((color >>> 16) & 0xFF) / 255f;
                        image[w * h + i] = ((color >>> 8) & 0xFF) / 255f;
                        image[2 * w * h + i] = (color & 0xFF) / 255f;
                        boolean painted = ((msk[i] >>> 24) & 0xFF) > 8 && ((msk[i] >>> 16) & 0xFF) > 0;
                        if (invert) painted = !painted;
                        mask[i] = painted ? holeValue : 0f;
                    }
                }
                for (String outputName : new String[]{"output", "prediction"}) {
                    try (OnnxTensor imgT = OnnxTensor.createTensor(env, FloatBuffer.wrap(image), new long[]{1, 3, h, w});
                         OnnxTensor mskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, 1, h, w})) {
                        Map<String, OnnxTensor> inputs = new HashMap<>();
                        inputs.put("image", imgT);
                        inputs.put("mask", mskT);
                        long start = System.currentTimeMillis();
                        try (OrtSession.Result res = session.run(inputs, Set.of(outputName))) {
                            long elapsed = System.currentTimeMillis() - start;
                            OnnxTensor out = res.get(outputName).map(v -> (OnnxTensor) v).orElse(null);
                            if (out == null) {
                                System.out.println(variant[0] + " / " + outputName + ": MISSING");
                                continue;
                            }
                            var shape = out.getInfo().getShape();
                            var fb = out.getFloatBuffer();
                            int n = w * h;
                            double holeDiff = 0, knownDiff = 0;
                            float outMin = Float.MAX_VALUE, outMax = -Float.MAX_VALUE;
                            int holeN = 0, knownN = 0;
                            for (int y = 0; y < h; y++) {
                                for (int x = 0; x < w; x++) {
                                    int i = y * w + x;
                                    boolean hole = x >= hx0 && x < hx1 && y >= hy0 && y < hy1;
                                    for (int c = 0; c < 3; c++) {
                                        float v = fb.get(c * n + i);
                                        outMin = Math.min(outMin, v);
                                        outMax = Math.max(outMax, v);
                                        int orig = (src[i] >>> (16 - 8 * c)) & 0xFF;
                                        double d = Math.abs(v - orig);
                                        if (hole) { holeDiff += d; holeN++; } else { knownDiff += d; knownN++; }
                                    }
                                }
                            }
                            System.out.printf(
                                    "%s / %s: %d ms, shape=%s, range=[%.1f..%.1f], meanDiffHole=%.1f, meanDiffKnown=%.2f%n",
                                    variant[0], outputName, elapsed, shape, outMin, outMax,
                                    holeDiff / Math.max(1, holeN), knownDiff / Math.max(1, knownN));
                        }
                    }
                }
            }
        }
    }
}
