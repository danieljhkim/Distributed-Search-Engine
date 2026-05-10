package com.danieljhkim.dsearch.ml.embedding;

import ai.djl.inference.Predictor;
import ai.djl.translate.TranslateException;
import com.danieljhkim.dsearch.common.config.AppConfig;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TextEmbeddingService implements TextEmbedder, Closeable {

    private static final Logger LOGGER = Logger.getLogger(TextEmbeddingService.class.getName());

    private final EmbeddingModelManager modelManager;
    private final boolean predictorPerCall;
    private final BlockingQueue<Predictor<String, float[]>> predictorPool;
    private final List<Predictor<String, float[]>> pooledPredictors;
    private final AtomicInteger embeddingDimension = new AtomicInteger();

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
        return booleanConfigValue(
                textEmbeddingConfig(modelManager), false, "isPredictorPerCall", "getPredictorPerCall");
    }

    private static int predictorPoolSize(EmbeddingModelManager modelManager) {
        return intConfigValue(textEmbeddingConfig(modelManager), 1, "getPredictorPoolSize");
    }

    private static AppConfig.TextEmbeddingConfig textEmbeddingConfig(EmbeddingModelManager modelManager) {
        return Objects.requireNonNull(modelManager, "modelManager")
                .getAppConfig()
                .getMl()
                .getModels()
                .getTextEmbedding();
    }

    private static boolean booleanConfigValue(
            AppConfig.TextEmbeddingConfig config, boolean defaultValue, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Object value = config.getClass().getMethod(methodName).invoke(config);
                if (value instanceof Boolean booleanValue) {
                    return booleanValue;
                }
            } catch (NoSuchMethodException e) {
                // Older dk.common snapshots did not expose this optional setting.
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to read text embedding config method: " + methodName, e);
            }
        }
        return defaultValue;
    }

    private static int intConfigValue(AppConfig.TextEmbeddingConfig config, int defaultValue, String methodName) {
        try {
            Object value = config.getClass().getMethod(methodName).invoke(config);
            if (value instanceof Number numberValue) {
                return numberValue.intValue();
            }
        } catch (NoSuchMethodException e) {
            // Older dk.common snapshots did not expose this optional setting.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read text embedding config method: " + methodName, e);
        }
        return defaultValue;
    }

    /**
     * Compute embedding for a single text
     */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }

        if (predictorPerCall) {
            return embedWithNewPredictor(text);
        }

        Predictor<String, float[]> predictor = borrowPredictor();
        try {
            return validateEmbedding(predictor.predict(text));
        } catch (TranslateException e) {
            LOGGER.log(Level.SEVERE, "Failed to compute embedding", e);
            throw new RuntimeException("Failed to compute embedding", e);
        } finally {
            returnPredictor(predictor);
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
        try {
            return predictorPool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for embedding predictor", e);
        }
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
        pooledPredictors.forEach(Predictor::close);
        pooledPredictors.clear();
        if (predictorPool != null) {
            predictorPool.clear();
        }
    }
}
