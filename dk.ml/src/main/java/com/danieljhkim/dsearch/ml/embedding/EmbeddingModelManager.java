package com.danieljhkim.dsearch.ml.embedding;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.danieljhkim.dsearch.common.config.AppConfig;
import com.danieljhkim.dsearch.common.config.ConfigLoader;

import ai.djl.ModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import lombok.Getter;

@Getter
public class EmbeddingModelManager {

	private static final Logger LOGGER = Logger.getLogger(EmbeddingModelManager.class.getName());
	private static volatile EmbeddingModelManager INSTANCE;
	private final AppConfig appConfig;
	private final String DEFAULT_MODEL_URL;
	private final String DEFAULT_ENGINE;
	private final Map<String, ZooModel<String, float[]>> modelCache = new ConcurrentHashMap<>();
	private ZooModel<String, float[]> defaultModel;

	private EmbeddingModelManager() throws IOException {
		this.appConfig = ConfigLoader.load();
		this.DEFAULT_MODEL_URL = appConfig.getMl().getModels().getTextEmbedding().getUrl();
		this.DEFAULT_ENGINE = appConfig.getMl().getModels().getTextEmbedding().getEngine();
	}

	public static EmbeddingModelManager getInstance() {
		if (INSTANCE == null) {
			synchronized (EmbeddingModelManager.class) {
				if (INSTANCE == null) {
					try {
						INSTANCE = new EmbeddingModelManager();
					} catch (IOException e) {
						LOGGER.log(Level.SEVERE, "Failed to initialize EmbeddingModelManager", e);
						throw new RuntimeException("Failed to initialize EmbeddingModelManager", e);
					}
					INSTANCE.init();
				}
			}
		}
		return INSTANCE;
	}

	private void init() {
		LOGGER.info(() -> "Loading default embedding model: " + DEFAULT_MODEL_URL);
		this.defaultModel = loadModel(DEFAULT_MODEL_URL, DEFAULT_ENGINE);
		modelCache.put(DEFAULT_MODEL_URL, this.defaultModel);
		LOGGER.info(() -> "Default embedding model loaded successfully: " + DEFAULT_MODEL_URL);
	}

	private ZooModel<String, float[]> loadModel(String modelUrl, String engine) {
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

	public ZooModel<String, float[]> getOrLoadModel(String modelUrl, String engine) {
		ZooModel<String, float[]> cached = modelCache.get(modelUrl);
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			cached = modelCache.get(modelUrl);
			if (cached != null) {
				return cached;
			}
			ZooModel<String, float[]> loaded = loadModel(modelUrl, engine);
			modelCache.put(modelUrl, loaded);
			return loaded;
		}
	}

	public void close() {
		modelCache.values().forEach(ZooModel::close);
		modelCache.clear();
		defaultModel = null;
	}
}