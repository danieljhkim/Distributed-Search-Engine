package com.danieljhkim.dsearch.ml.embedding;

import ai.djl.inference.Predictor;
import ai.djl.translate.TranslateException;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.schema.EmbeddingModelIdentity;
import com.danieljhkim.dsearch.common.validation.RequestAdmissionException;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TextEmbeddingService implements TextEmbedder, Closeable {

    private static final Logger LOGGER = Logger.getLogger(TextEmbeddingService.class.getName());
    private static final Counter INFERENCE_OUTCOMES = Counter.build()
            .name("dsearch_embedding_inference_outcomes_total")
            .help("Embedding inference attempts by bounded outcome")
            .labelNames("outcome")
            .register();
    private static final Gauge INFERENCE_IN_FLIGHT = Gauge.build()
            .name("dsearch_embedding_inference_in_flight")
            .help("Embedding inferences currently holding a predictor")
            .register();
    private static final Histogram INFERENCE_DURATION = Histogram.build()
            .name("dsearch_embedding_inference_duration_seconds")
            .help("Duration of embedding inference attempts")
            .register();

    private final EmbeddingModelManager modelManager;
    private final boolean predictorPerCall;
    private final BlockingQueue<Predictor<String, float[]>> predictorPool;
    private final List<Predictor<String, float[]>> pooledPredictors;
    private final Semaphore predictorAdmission;
    private final int retryAfterMillis;
    private final AtomicInteger embeddingDimension = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    public TextEmbeddingService() {
        this(EmbeddingModelManager.getInstance());
    }

    TextEmbeddingService(EmbeddingModelManager modelManager) {
        this(modelManager, predictorPerCall(modelManager), predictorPoolSize(modelManager));
    }

    TextEmbeddingService(EmbeddingModelManager modelManager, boolean predictorPerCall, int predictorPoolSize) {
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager");
        this.predictorPerCall = predictorPerCall;
        predictorPoolSize = Math.max(1, predictorPoolSize);
        this.predictorAdmission = new Semaphore(predictorPoolSize, true);
        AppConfig.RequestLimitsConfig requestLimits =
                modelManager.getAppConfig().getRequestLimits();
        this.retryAfterMillis = Math.max(1, requestLimits != null ? requestLimits.getRetryAfterMillis() : 100);
        if (predictorPerCall) {
            this.predictorPool = null;
            this.pooledPredictors = new ArrayList<>();
        } else {
            this.predictorPool = new ArrayBlockingQueue<>(predictorPoolSize);
            this.pooledPredictors = new ArrayList<>(predictorPoolSize);
            for (int i = 0; i < predictorPoolSize; i++) {
                Predictor<String, float[]> predictor =
                        modelManager.getDefaultModel().newPredictor();
                predictorPool.add(predictor);
                pooledPredictors.add(predictor);
            }
        }
    }

    private static boolean predictorPerCall(EmbeddingModelManager modelManager) {
        return textEmbeddingConfig(modelManager).isPredictorPerCall();
    }

    private static int predictorPoolSize(EmbeddingModelManager modelManager) {
        return textEmbeddingConfig(modelManager).getPredictorPoolSize();
    }

    private static AppConfig.TextEmbeddingConfig textEmbeddingConfig(EmbeddingModelManager modelManager) {
        return Objects.requireNonNull(modelManager, "modelManager")
                .getAppConfig()
                .getMl()
                .getModels()
                .getTextEmbedding();
    }

    /**
     * Compute embedding for a single text
     */
    @Override
    public float[] embed(String text) {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return embedOpen(text);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** Computes embeddings in input order, preserving the empty-input policy for each element. */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            embeddings.add(embed(text));
        }
        return embeddings;
    }

    /**
     * Exposes model admission state without borrowing a predictor. This is used by node readiness
     * checks and remains independent of request traffic.
     */
    public boolean isReady() {
        return !closed.get() && modelManager.isDefaultModelReady();
    }

    @Override
    public EmbeddingModelIdentity identity() {
        AppConfig.TextEmbeddingConfig config = textEmbeddingConfig(modelManager);
        int dimension = config.getDimension() > 0 ? config.getDimension() : embeddingDimension.get();
        return EmbeddingModelIdentity.of(config.getUrl(), config.getEngine(), dimension);
    }

    private float[] embedOpen(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }

        if (!predictorAdmission.tryAcquire()) {
            INFERENCE_OUTCOMES.labels("rejected").inc();
            throw new RequestAdmissionException("embedding predictor", retryAfterMillis);
        }
        INFERENCE_IN_FLIGHT.inc();
        long started = System.nanoTime();
        try {
            if (predictorPerCall) {
                float[] embedding = embedWithNewPredictor(text);
                INFERENCE_OUTCOMES.labels("success").inc();
                return embedding;
            }

            Predictor<String, float[]> predictor = borrowPredictor();
            try {
                float[] embedding = validateEmbedding(predictor.predict(text));
                INFERENCE_OUTCOMES.labels("success").inc();
                return embedding;
            } catch (TranslateException e) {
                INFERENCE_OUTCOMES.labels("failure").inc();
                LOGGER.log(Level.SEVERE, "Failed to compute embedding", e);
                throw new RuntimeException("Failed to compute embedding", e);
            } finally {
                returnPredictor(predictor);
            }
        } finally {
            INFERENCE_DURATION.observe((System.nanoTime() - started) / 1_000_000_000.0);
            INFERENCE_IN_FLIGHT.dec();
            predictorAdmission.release();
        }
    }

    private float[] embedWithNewPredictor(String text) {
        try (Predictor<String, float[]> predictor =
                modelManager.getDefaultModel().newPredictor()) {
            return validateEmbedding(predictor.predict(text));
        } catch (TranslateException e) {
            LOGGER.log(Level.SEVERE, "Failed to compute embedding", e);
            throw new RuntimeException("Failed to compute embedding", e);
        }
    }

    private float[] validateEmbedding(float[] embedding) {
        if (embedding == null) {
            throw new IllegalStateException("Embedding predictor returned null");
        }
        if (embedding.length == 0) {
            throw new IllegalStateException("Embedding predictor returned an empty vector");
        }

        int knownDimension = embeddingDimension.get();
        if (knownDimension == 0 && embeddingDimension.compareAndSet(0, embedding.length)) {
            return embedding;
        }

        int expectedDimension = embeddingDimension.get();
        if (embedding.length != expectedDimension) {
            throw new IllegalStateException(
                    "Embedding dimension changed from " + expectedDimension + " to " + embedding.length);
        }
        return embedding;
    }

    private Predictor<String, float[]> borrowPredictor() {
        Predictor<String, float[]> predictor = predictorPool.poll();
        if (predictor == null) {
            throw new RequestAdmissionException("embedding predictor", retryAfterMillis);
        }
        return predictor;
    }

    private void returnPredictor(Predictor<String, float[]> predictor) {
        if (predictor == null) {
            return;
        }
        if (!predictorPool.offer(predictor)) {
            LOGGER.warning("Embedding predictor pool was full while returning a predictor");
        }
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            pooledPredictors.forEach(Predictor::close);
            pooledPredictors.clear();
            if (predictorPool != null) {
                predictorPool.clear();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Text embedding service is closed");
        }
    }
}
