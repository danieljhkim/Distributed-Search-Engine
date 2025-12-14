package com.danieljhkim.dsearch.ml.embedding;

import java.util.logging.Level;
import java.util.logging.Logger;

import ai.djl.inference.Predictor;
import ai.djl.translate.TranslateException;

//TODO: keeping a pool of Predictors, adding batching, caching embeddings etc. for performance improvements

public class TextEmbeddingService {

	private static final Logger LOGGER = Logger.getLogger(TextEmbeddingService.class.getName());

	private final EmbeddingModelManager modelManager;

	public TextEmbeddingService() {
		this.modelManager = EmbeddingModelManager.getInstance();
	}

	/**
	 * Compute embedding for a single text
	 */
	public float[] embed(String text) {
		if (text == null || text.isBlank()) {
			return new float[0];
		}

		try (Predictor<String, float[]> predictor = modelManager.getDefaultModel().newPredictor()) {
			return predictor.predict(text);
		} catch (TranslateException e) {
			LOGGER.log(Level.SEVERE, "Failed to compute embedding", e);
			throw new RuntimeException("Failed to compute embedding", e);
		}
	}
}