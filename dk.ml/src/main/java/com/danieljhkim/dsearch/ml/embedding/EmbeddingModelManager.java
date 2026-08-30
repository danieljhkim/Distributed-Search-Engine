package com.danieljhkim.dsearch.ml.embedding;

import ai.djl.ModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;

@Getter
public class EmbeddingModelManager {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingModelManager.class.getName());
    private static volatile EmbeddingModelManager INSTANCE;
    private final AppConfig appConfig;
    private final String DEFAULT_MODEL_URL;
    private final String DEFAULT_ENGINE;
    private final ModelLoader modelLoader;
    private final Map<String, ZooModel<String, float[]>> modelCache = new ConcurrentHashMap<>();
    private volatile ZooModel<String, float[]> defaultModel;
    private volatile boolean closed;

    private EmbeddingModelManager() throws IOException {
        this(ConfigLoader.load(), EmbeddingModelManager::loadModel);
    }

    EmbeddingModelManager(AppConfig appConfig, ModelLoader modelLoader) {
        this.appConfig = Objects.requireNonNull(appConfig, "appConfig");
        this.DEFAULT_MODEL_URL =
                appConfig.getMl().getModels().getTextEmbedding().getUrl();
        this.DEFAULT_ENGINE = appConfig.getMl().getModels().getTextEmbedding().getEngine();
        this.modelLoader = Objects.requireNonNull(modelLoader, "modelLoader");
    }

    public static EmbeddingModelManager getInstance() {
        synchronized (EmbeddingModelManager.class) {
            if (INSTANCE == null) {
                try {
                    INSTANCE = new EmbeddingModelManager();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Failed to initialize EmbeddingModelManager", e);
                    throw new RuntimeException("Failed to initialize EmbeddingModelManager", e);
                }
            }
            if (!INSTANCE.isDefaultModelReady()) {
                INSTANCE.initializeDefaultModel();
            }
        }
        return INSTANCE;
    }

    synchronized void initializeDefaultModel() {
        ensureOpen();
        LOGGER.info(() -> "Loading default embedding model: " + DEFAULT_MODEL_URL);
        this.defaultModel = loadAndCache(DEFAULT_MODEL_URL, DEFAULT_ENGINE);
        LOGGER.info(() -> "Default embedding model loaded successfully: " + DEFAULT_MODEL_URL);
    }

    private static ZooModel<String, float[]> loadModel(String modelUrl, String engine) {
        try {
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls(modelUrl)
                    .optEngine(engine)
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .optProgress(new ProgressBar())
                    .build();
            return criteria.loadModel();
        } catch (IOException | ModelException e) {
            LOGGER.log(Level.SEVERE, "Failed to load embedding model: " + modelUrl, e);
            throw new RuntimeException("Failed to load embedding model: " + modelUrl, e);
        }
    }

    public synchronized ZooModel<String, float[]> getOrLoadModel(String modelUrl, String engine) {
        ensureOpen();
        Objects.requireNonNull(modelUrl, "modelUrl");
        Objects.requireNonNull(engine, "engine");
        ZooModel<String, float[]> cached = modelCache.get(modelUrl);
        if (cached != null) {
            return cached;
        }
        return loadAndCache(modelUrl, engine);
    }

    /** True only after the configured default embedding model has loaded and before shutdown. */
    public boolean isDefaultModelReady() {
        return !closed && defaultModel != null;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        modelCache.values().stream().distinct().forEach(ZooModel::close);
        modelCache.clear();
        defaultModel = null;
    }

    private ZooModel<String, float[]> loadAndCache(String modelUrl, String engine) {
        ZooModel<String, float[]> loaded = Objects.requireNonNull(modelLoader.load(modelUrl, engine), "loaded model");
        modelCache.put(modelUrl, loaded);
        return loaded;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Embedding model manager is closed");
        }
    }

    @FunctionalInterface
    interface ModelLoader {
        ZooModel<String, float[]> load(String modelUrl, String engine);
    }
}
