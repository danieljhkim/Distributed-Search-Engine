package com.danieljhkim.dsearch.ml.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import com.danieljhkim.dsearch.common.config.AppConfig;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class TextEmbeddingServiceTest {

    @Test
    void embedReturnsPredictorOutputFromPool() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("hello")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});

        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);

        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, service.embed("hello"));
        verify(predictor).predict("hello");

        service.close();
        verify(predictor).close();
    }

    @Test
    void embedReturnsEmptyForBlankInputWithoutCreatingPredictor() {
        ZooModel<String, float[]> model = mockModel();
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), true, 1);

        assertArrayEquals(new float[0], service.embed(null));
        assertArrayEquals(new float[0], service.embed(" \t\n"));
        verifyNoInteractions(model);

        service.close();
    }

    @Test
    void embedIsDeterministicForRepeatedInput() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("same")).thenReturn(new float[] {1.0f, 2.0f, 3.0f}, new float[] {1.0f, 2.0f, 3.0f});
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);

        float[] first = service.embed("same");
        float[] second = service.embed("same");

        assertArrayEquals(first, second);
        assertEquals(3, first.length);
        verify(predictor, times(2)).predict("same");

        service.close();
    }

    @Test
    void embedRejectsDimensionChanges() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("first")).thenReturn(new float[] {1.0f, 2.0f, 3.0f});
        when(predictor.predict("second")).thenReturn(new float[] {1.0f, 2.0f});
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);

        assertArrayEquals(new float[] {1.0f, 2.0f, 3.0f}, service.embed("first"));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.embed("second"));

        assertTrue(exception.getMessage().contains("3 to 2"));

        service.close();
    }

    @Test
    void embedMapsTranslateExceptionToRuntimeException() throws Exception {
        Logger logger = Logger.getLogger(TextEmbeddingService.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            Predictor<String, float[]> predictor = mockPredictor();
            ZooModel<String, float[]> model = mockModel();
            when(model.newPredictor()).thenReturn(predictor);
            TranslateException translateException = new TranslateException("boom");
            when(predictor.predict("boom")).thenThrow(translateException);
            TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), true, 1);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> service.embed("boom"));

            assertEquals("Failed to compute embedding", exception.getMessage());
            assertInstanceOf(TranslateException.class, exception.getCause());
            verify(predictor).close();

            service.close();
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void closeClosesAllPooledPredictors() throws Exception {
        Predictor<String, float[]> firstPredictor = mockPredictor();
        Predictor<String, float[]> secondPredictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(firstPredictor, secondPredictor);
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 2);

        service.close();

        verify(firstPredictor).close();
        verify(secondPredictor).close();
        verify(firstPredictor, never()).predict("unused");
        verify(secondPredictor, never()).predict("unused");
    }

    private static EmbeddingModelManager manager(ZooModel<String, float[]> model, AppConfig config) {
        EmbeddingModelManager manager = mock(EmbeddingModelManager.class);
        when(manager.getAppConfig()).thenReturn(config);
        when(manager.getDefaultModel()).thenReturn(model);
        return manager;
    }

    private static AppConfig config() {
        AppConfig.TextEmbeddingConfig textEmbeddingConfig = new AppConfig.TextEmbeddingConfig();
        textEmbeddingConfig.setUrl("fake-model");
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
    private static Predictor<String, float[]> mockPredictor() {
        return mock(Predictor.class);
    }

    @SuppressWarnings("unchecked")
    private static ZooModel<String, float[]> mockModel() {
        return mock(ZooModel.class);
    }
}
