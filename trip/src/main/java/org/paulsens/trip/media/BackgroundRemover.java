package org.paulsens.trip.media;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Cuts the subject out of a profile picture: U^2-Net-small (u2netp, Apache-2.0) under ONNX Runtime, answering
 * a {@link PhotoProcessor#PROFILE_SIZE}-square RGBA PNG whose alpha channel is the predicted foreground mask.
 * The caller composites it over whatever background it likes; this class never touches storage.
 *
 * <p><b>Memory is the constraint, not speed.</b> The 2 GB task has an OOM history, so inference is bounded
 * hard: ONE permit ({@link #GATE}) — a second request while one runs waits briefly and then reports "busy"
 * rather than queueing work — and the input is downscaled to {@link #MODEL_SIZE} before the tensor is built,
 * so peak transient cost is one 320x320 float tensor plus the session's own workspace. The session itself is
 * created lazily on FIRST use, never at boot (the feature ships flag-off, and a disabled feature must cost
 * zero), and is CLOSED again after {@link #IDLE_CLOSE_MILLIS} without use: the loaded session retains
 * ~440 MB of native arena, and on a 2 GB task whose heap ceiling is 1.5 GB that residency would be a
 * permanent over-commit. Occasional use pays a ~1s reload instead.
 *
 * <p>The model file lives on the classpath ({@value #MODEL_RESOURCE}). Absent — a build that chose not to
 * carry it — the feature reports {@link #isAvailable()} false and callers degrade to a friendly message.
 */
@Slf4j
public final class BackgroundRemover {

    static final String MODEL_RESOURCE = "/models/u2netp.onnx";

    /** u2netp's fixed input side. */
    static final int MODEL_SIZE = 320;

    /** ImageNet normalization, the training-time preprocessing this model expects. */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /** One inference at a time, process-wide; waiting longer than this reports busy instead. */
    static final Semaphore GATE = new Semaphore(1);
    private static final long GATE_WAIT_SECONDS = 5;

    /**
     * How long the loaded session may sit unused before the reaper closes it. The session retains ~440 MB of
     * native memory (measured, 2026-08-12) — permanent residency on the 2 GB task over-commits it against the
     * heap's own ceiling, so an occasional-use feature gives the memory back and re-pays the ~1s model load
     * on the next use instead.
     */
    static final long IDLE_CLOSE_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long REAPER_PERIOD_SECONDS = 60;

    private static volatile OrtSession session;
    private static volatile boolean sessionFailed;
    private static volatile long lastUsedAt;
    private static volatile long idleCloseMillis = IDLE_CLOSE_MILLIS;
    /** Created with the first session and kept for the process's life; one thread, daemon, mostly asleep. */
    private static volatile ScheduledExecutorService reaper;
    /** The classpath location actually consulted; a test points it at garbage to exercise failure paths. */
    private static volatile String modelResource = MODEL_RESOURCE;

    private BackgroundRemover() {
    }

    /** Whether the model is on the classpath (cheap; does NOT load the session). */
    public static boolean isAvailable() {
        if (sessionFailed) {
            return false;
        }
        return BackgroundRemover.class.getResource(modelResource) != null;
    }

    /**
     * @param jpeg512 the current profile rendition (any square image works; it is rescaled as needed).
     * @return the RGBA cutout PNG, or empty when another inference holds the permit (busy) or the model is
     *         unavailable/broken. Never throws for those; a corrupt INPUT still throws PhotoRejectedException.
     */
    public static Optional<byte[]> cutout(final byte[] jpeg512) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        final boolean acquired;
        try {
            acquired = GATE.tryAcquire(GATE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!acquired) {
            log.info("Background removal busy; a second request backed off");
            return Optional.empty();
        }
        try {
            lastUsedAt = System.currentTimeMillis();
            return Optional.ofNullable(runCutout(jpeg512));
        } finally {
            // Stamped again on the way out so a long inference doesn't eat into its own idle window.
            lastUsedAt = System.currentTimeMillis();
            GATE.release();
        }
    }

    private static byte[] runCutout(final byte[] imageBytes) {
        final BufferedImage source = decodeSquare(imageBytes);
        final OrtSession ort = orCreateSession();
        if (ort == null) {
            return null;
        }
        try {
            final float[][] mask = infer(ort, source);
            return PhotoProcessor.encodePng(applyMask(source, mask));
        } catch (final OrtException | RuntimeException ex) {
            log.error("Background removal inference failed", ex);
            return null;
        }
    }

    private static BufferedImage decodeSquare(final byte[] imageBytes) {
        // processProfile validates, re-squares and re-scales defensively; input is normally the 512 rendition.
        return PhotoProcessor.decode(new PhotoProcessor().processProfile(imageBytes, null), ImageFormat.JPEG);
    }

    /** Lazy, once; a failed creation is remembered so a broken native setup fails fast, not per click. */
    private static OrtSession orCreateSession() {
        OrtSession existing = session;
        if (existing != null || sessionFailed) {
            return existing;
        }
        synchronized (BackgroundRemover.class) {
            if (session != null || sessionFailed) {
                return session;
            }
            try (InputStream in = BackgroundRemover.class.getResourceAsStream(modelResource)) {
                if (in == null) {
                    sessionFailed = true;
                    return null;
                }
                final byte[] model = in.readAllBytes();
                session = OrtEnvironment.getEnvironment().createSession(model);
                log.info("Background-removal model loaded ({} KB)", model.length / 1024);
                startReaper();
            } catch (final OrtException | IOException | RuntimeException | Error ex) {
                log.error("Background-removal model could not be loaded; the feature reports unavailable", ex);
                sessionFailed = true;
            }
            return session;
        }
    }

    private static float[][] infer(final OrtSession ort, final BufferedImage source) throws OrtException {
        final BufferedImage small = PhotoProcessor.scaleToExact(source, MODEL_SIZE, MODEL_SIZE);
        final FloatBuffer input = FloatBuffer.allocate(3 * MODEL_SIZE * MODEL_SIZE);
        for (int channel = 0; channel < 3; channel++) {
            for (int y = 0; y < MODEL_SIZE; y++) {
                for (int x = 0; x < MODEL_SIZE; x++) {
                    final int rgb = small.getRGB(x, y);
                    final int value = (rgb >> (16 - 8 * channel)) & 0xFF;
                    input.put(((value / 255f) - MEAN[channel]) / STD[channel]);
                }
            }
        }
        input.rewind();
        final OrtEnvironment env = OrtEnvironment.getEnvironment();
        final String inputName = ort.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input,
                    new long[] {1, 3, MODEL_SIZE, MODEL_SIZE});
                OrtSession.Result result = ort.run(Map.of(inputName, tensor))) {
            final float[][][][] out = (float[][][][]) result.get(0).getValue();
            return normalized(out[0][0]);
        }
    }

    /** Min-max normalization of the raw saliency map — the model's outputs are not guaranteed 0..1. */
    static float[][] normalized(final float[][] mask) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (final float[] row : mask) {
            for (final float value : row) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        final float range = Math.max(max - min, 1e-6f);
        final float[][] out = new float[mask.length][mask[0].length];
        for (int y = 0; y < mask.length; y++) {
            for (int x = 0; x < mask[0].length; x++) {
                out[y][x] = (mask[y][x] - min) / range;
            }
        }
        return out;
    }

    /** The mask (model-sized) becomes the alpha channel of the full-size source. */
    static BufferedImage applyMask(final BufferedImage source, final float[][] mask) {
        final int size = source.getWidth();
        final BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final float scale = mask.length / (float) size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final int my = Math.min(mask.length - 1, Math.round(y * scale));
                final int mx = Math.min(mask[0].length - 1, Math.round(x * scale));
                final int alpha = Math.round(mask[my][mx] * 255);
                out.setRGB(x, y, (alpha << 24) | (source.getRGB(x, y) & 0xFFFFFF));
            }
        }
        return out;
    }

    /** Started once, with the first session; kept across idle closes (re-creating schedulers churns). */
    private static void startReaper() {
        if (reaper != null) {
            return;
        }
        reaper = Executors.newSingleThreadScheduledExecutor(BackgroundRemover::reaperThread);
        reaper.scheduleWithFixedDelay(BackgroundRemover::closeIfIdle,
                REAPER_PERIOD_SECONDS, REAPER_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private static Thread reaperThread(final Runnable task) {
        final Thread thread = new Thread(task, "bg-remover-idle-reaper");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Closes the session once it has sat idle past the window, giving its ~440 MB back; the next use simply
     * reloads. Takes the SAME permit inference holds, so a session is never closed mid-run — and a held
     * permit means "in use", which is its own reason not to close.
     */
    static void closeIfIdle() {
        if (session == null || System.currentTimeMillis() - lastUsedAt < idleCloseMillis) {
            return;
        }
        if (!GATE.tryAcquire()) {
            return;
        }
        try {
            synchronized (BackgroundRemover.class) {
                if (session != null && System.currentTimeMillis() - lastUsedAt >= idleCloseMillis) {
                    session.close();
                    session = null;
                    log.info("Background-removal session closed after {} min idle; memory reclaimed",
                            TimeUnit.MILLISECONDS.toMinutes(idleCloseMillis));
                }
            }
        } catch (final OrtException ex) {
            // The reference is dropped regardless; a close failure leaks at worst what was already resident.
            session = null;
            log.warn("Background-removal session close failed; reference dropped", ex);
        } finally {
            GATE.release();
        }
    }

    /** Whether a session is currently loaded (and holding its native memory). */
    static boolean isSessionLoaded() {
        return session != null;
    }

    /** Test seam: shrinks the idle window so reclamation is testable without waiting minutes. */
    static void idleCloseMillisForTest(final long millis) {
        idleCloseMillis = millis;
    }

    /** Test seam: back to a pristine state — real model location, no remembered failure, no live session. */
    static void resetForTest() {
        synchronized (BackgroundRemover.class) {
            if (session != null) {
                try {
                    session.close();
                } catch (final OrtException ex) {
                    log.warn("Session close failed during test reset", ex);
                }
                session = null;
            }
            sessionFailed = false;
            modelResource = MODEL_RESOURCE;
            idleCloseMillis = IDLE_CLOSE_MILLIS;
        }
    }

    /** Test seam: points the loader at a different classpath location (garbage, absent, ...). */
    static void modelResourceForTest(final String resource) {
        modelResource = resource;
    }
}
