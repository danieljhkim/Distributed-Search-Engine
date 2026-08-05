package com.danieljhkim.dsearch.ml.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.djl.repository.zoo.ZooModel;
import com.danieljhkim.dsearch.common.config.AppConfig;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EmbeddingModelManagerTest {

    @Test
    void getOrLoadModelLoadsLazilyAndCachesByModelUrl() {
        ZooModel<String, float[]> model = mockModel();
        AtomicInteger loadCount = new AtomicInteger();
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> {
            assertEquals("custom-model", modelUrl);
            assertEquals("fake-engine", engine);
            loadCount.incrementAndGet();
            return model;
        });

        assertEquals(0, loadCount.get());
        assertSame(model, manager.getOrLoadModel("custom-model", "fake-engine"));
        assertSame(model, manager.getOrLoadModel("custom-model", "ignored-engine"));
        assertEquals(1, loadCount.get());
    }

    @Test
    void closeClosesCachedModelsAndClearsState() {
        ZooModel<String, float[]> firstModel = mockModel();
        ZooModel<String, float[]> secondModel = mockModel();
        Map<String, ZooModel<String, float[]>> models = Map.of("first-model", firstModel, "second-model", secondModel);
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> models.get(modelUrl));

        manager.getOrLoadModel("first-model", "fake-engine");
        manager.getOrLoadModel("second-model", "fake-engine");

        manager.close();

        verify(firstModel).close();
        verify(secondModel).close();
        assertTrue(manager.getModelCache().isEmpty());
        assertNull(manager.getDefaultModel());
    }

    @Test
    void constructorRequiresConfigAndLoader() {
        assertThrows(
                NullPointerException.class, () -> new EmbeddingModelManager(null, (modelUrl, engine) -> mockModel()));
        assertThrows(NullPointerException.class, () -> new EmbeddingModelManager(config(), null));
    }

    private static AppConfig config() {
        AppConfig.TextEmbeddingConfig textEmbeddingConfig = new AppConfig.TextEmbeddingConfig();
        textEmbeddingConfig.setUrl("default-model");
        textEmbeddingConfig.setEngine("fake-engine");

        AppConfig.ModelsConfig modelsConfig = new AppConfig.ModelsConfig();
        modelsConfig.setTextEmbedding(textEmbeddingConfig);

        AppConfig.MlConfig mlConfig = new AppConfig.MlConfig();
        mlConfig.setModels(modelsConfig);

        AppConfig appConfig = new AppConfig();
        appConfig.setMl(mlConfig);
        return appConfig;
    }

    @SuppressWarnings("unchecked")
    private static ZooModel<String, float[]> mockModel() {
        return mock(ZooModel.class);
    }
}
