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
import com.danieljhkim.dsearch.common.validation.RequestAdmissionException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void batchEmbeddingPreservesInputOrderAndEmptyInputPolicy() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("first")).thenReturn(new float[] {1.0f, 2.0f});
        when(predictor.predict("second")).thenReturn(new float[] {3.0f, 4.0f});
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);

        List<float[]> embeddings = service.embedAll(java.util.Arrays.asList("first", null, "second"));

        assertEquals(3, embeddings.size());
        assertArrayEquals(new float[] {1.0f, 2.0f}, embeddings.get(0));
        assertArrayEquals(new float[0], embeddings.get(1));
        assertArrayEquals(new float[] {3.0f, 4.0f}, embeddings.get(2));
        service.close();
    }

    @Test
    void nullAndEmptyBatchesDoNotCreatePredictors() {
        ZooModel<String, float[]> model = mockModel();
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), true, 1);

        assertTrue(service.embedAll(null).isEmpty());
        assertTrue(service.embedAll(List.of()).isEmpty());
        verifyNoInteractions(model);

        service.close();
    }

    @Test
    void configConstructorUsesConfiguredPoolSizeWithoutLoadingAModel() {
        Predictor<String, float[]> firstPredictor = mockPredictor();
        Predictor<String, float[]> secondPredictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(firstPredictor, secondPredictor);
        AppConfig configured = config();
        configured.getMl().getModels().getTextEmbedding().setPredictorPoolSize(2);
        TextEmbeddingService service = new TextEmbeddingService(manager(model, configured));

        service.close();

        verify(firstPredictor).close();
        verify(secondPredictor).close();
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
    void embedRejectsNullAndEmptyPredictorResults() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("null")).thenReturn((float[]) null);
        when(predictor.predict("empty")).thenReturn(new float[0]);
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);

        assertTrue(assertThrows(IllegalStateException.class, () -> service.embed("null"))
                .getMessage()
                .contains("null"));
        assertTrue(assertThrows(IllegalStateException.class, () -> service.embed("empty"))
                .getMessage()
                .contains("empty"));
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

    @Test
    void closeIsIdempotentAndRefusesNewEmbeddings() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);

        service.close();
        service.close();

        verify(predictor, times(1)).close();
        assertThrows(IllegalStateException.class, () -> service.embed("after-close"));
    }

    @Test
    void successfulPerCallEmbeddingClosesItsPredictor() throws Exception {
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("one-shot")).thenReturn(new float[] {1.0f});
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), true, 1);

        assertArrayEquals(new float[] {1.0f}, service.embed("one-shot"));
        verify(predictor).close();
        service.close();
    }

    @Test
    void concurrentPredictorOverloadIsRejectedWithoutWaiting() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Predictor<String, float[]> predictor = mockPredictor();
        ZooModel<String, float[]> model = mockModel();
        when(model.newPredictor()).thenReturn(predictor);
        when(predictor.predict("first")).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new float[] {1.0f};
        });
        TextEmbeddingService service = new TextEmbeddingService(manager(model, config()), false, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.embed("first"));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            RequestAdmissionException overload =
                    assertThrows(RequestAdmissionException.class, () -> service.embed("second"));
            assertTrue(overload.getMessage().contains("retry after"));
            verify(predictor, never()).predict("second");

            release.countDown();
            assertArrayEquals(new float[] {1.0f}, first.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            service.close();
        }
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
