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
import java.util.logging.Level;
import java.util.logging.Logger;

public class TextEmbeddingService implements TextEmbedder, Closeable {

    private static final Logger LOGGER = Logger.getLogger(TextEmbeddingService.class.getName());

    private final EmbeddingModelManager modelManager;
    private final boolean predictorPerCall;
    private final BlockingQueue<Predictor<String, float[]>> predictorPool;
    private final List<Predictor<String, float[]>> pooledPredictors;

    public TextEmbeddingService() {
        this(EmbeddingModelManager.getInstance());
    }

    TextEmbeddingService(EmbeddingModelManager modelManager) {
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager");
        AppConfig.TextEmbeddingConfig config =
                modelManager.getAppConfig().getMl().getModels().getTextEmbedding();
        this.predictorPerCall = config.isPredictorPerCall();
        int predictorPoolSize = Math.max(1, config.getPredictorPoolSize());
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
            return predictor.predict(text);
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
            return predictor.predict(text);
        } catch (TranslateException e) {
            LOGGER.log(Level.SEVERE, "Failed to compute embedding", e);
            throw new RuntimeException("Failed to compute embedding", e);
        }
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
