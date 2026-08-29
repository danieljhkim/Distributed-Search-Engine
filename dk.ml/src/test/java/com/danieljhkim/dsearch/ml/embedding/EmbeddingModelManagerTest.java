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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        manager.close();

        verify(firstModel).close();
        verify(secondModel).close();
        assertTrue(manager.getModelCache().isEmpty());
        assertNull(manager.getDefaultModel());
    }

    @Test
    void failedLoadIsNotCachedAndCanBeRetried() {
        ZooModel<String, float[]> model = mockModel();
        AtomicInteger attempts = new AtomicInteger();
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalArgumentException("unsupported model");
            }
            return model;
        });

        assertThrows(IllegalArgumentException.class, () -> manager.getOrLoadModel("unsupported", "fake"));
        assertTrue(manager.getModelCache().isEmpty());
        assertSame(model, manager.getOrLoadModel("unsupported", "fake"));
        assertEquals(2, attempts.get());
    }

    @Test
    void rejectsNullModelDetailsAndNullLoadedModel() {
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> null);

        assertThrows(NullPointerException.class, () -> manager.getOrLoadModel(null, "fake"));
        assertThrows(NullPointerException.class, () -> manager.getOrLoadModel("model", null));
        assertThrows(NullPointerException.class, () -> manager.getOrLoadModel("model", "fake"));
        assertTrue(manager.getModelCache().isEmpty());
    }

    @Test
    void parallelLoadCreatesAndClosesOneModelExactlyOnce() throws Exception {
        ZooModel<String, float[]> model = mockModel();
        AtomicInteger loadCount = new AtomicInteger();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch allowLoad = new CountDownLatch(1);
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> {
            loadCount.incrementAndGet();
            loadStarted.countDown();
            await(allowLoad);
            return model;
        });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<ZooModel<String, float[]>>> futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> manager.getOrLoadModel("shared", "fake")))
                    .toList();

            assertTrue(loadStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, loadCount.get());
            allowLoad.countDown();
            for (Future<ZooModel<String, float[]>> future : futures) {
                assertSame(model, future.get(5, TimeUnit.SECONDS));
            }

            manager.close();
            verify(model).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeWaitsForAnInFlightLoadAndRejectsFutureLoads() throws Exception {
        ZooModel<String, float[]> model = mockModel();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch allowLoad = new CountDownLatch(1);
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> {
            loadStarted.countDown();
            await(allowLoad);
            return model;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ZooModel<String, float[]>> load = executor.submit(() -> manager.getOrLoadModel("race", "fake"));
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS));
            Future<?> close = executor.submit(manager::close);

            allowLoad.countDown();
            assertSame(model, load.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);

            verify(model).close();
            assertThrows(IllegalStateException.class, () -> manager.getOrLoadModel("race", "fake"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void constructorRequiresConfigAndLoader() {
        assertThrows(
                NullPointerException.class, () -> new EmbeddingModelManager(null, (modelUrl, engine) -> mockModel()));
        assertThrows(NullPointerException.class, () -> new EmbeddingModelManager(config(), null));
    }

    @Test
    void initSetsTheDefaultModelWithoutUsingAProductionLoader() throws Exception {
        ZooModel<String, float[]> model = mockModel();
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> model);
        Method init = EmbeddingModelManager.class.getDeclaredMethod("init");
        init.setAccessible(true);

        init.invoke(manager);

        assertSame(model, manager.getDefaultModel());
        assertSame(model, manager.getModelCache().get("default-model"));
    }

    @Test
    void getInstanceReturnsAnExistingSingletonWithoutLoadingAModel() throws Exception {
        EmbeddingModelManager manager = new EmbeddingModelManager(config(), (modelUrl, engine) -> mockModel());
        Field instance = EmbeddingModelManager.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        try {
            instance.set(null, manager);
            assertSame(manager, EmbeddingModelManager.getInstance());
        } finally {
            instance.set(null, previous);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test synchronization");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for test synchronization", e);
        }
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
