package com.danieljhkim.dsearch.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danieljhkim.dsearch.common.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestAdmissionFilterTest {

    @Test
    void oversizedHttpBodyIsRejectedBeforeControllerDispatch() throws Exception {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxHttpBodyBytes(8);
        RequestAdmissionFilter filter = new RequestAdmissionFilter(limits);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/index/bulk");
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> dispatched.set(true));

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("maximum allowed (8)"));
        assertFalse(dispatched.get());
    }

    @Test
    void admittedBodyRemainsReadableByTheController() throws Exception {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxHttpBodyBytes(64);
        RequestAdmissionFilter filter = new RequestAdmissionFilter(limits);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/search");
        request.setContent("{\"query\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean bodyReadable = new AtomicBoolean();

        filter.doFilter(
                request,
                response,
                (wrappedRequest, ignoredResponse) -> bodyReadable.set(
                        new String(wrappedRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                                .contains("\"ok\"")));

        assertEquals(200, response.getStatus());
        assertTrue(bodyReadable.get());
    }

    @Test
    void concurrentHttpOverloadReturnsRetryGuidanceWithoutQueuing() throws Exception {
        AppConfig.RequestLimitsConfig limits = new AppConfig.RequestLimitsConfig();
        limits.setMaxConcurrentHttpRequests(1);
        RequestAdmissionFilter filter = new RequestAdmissionFilter(limits);
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/index/bulk");
                filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
                    admitted.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while waiting for request release", e);
                    }
                });
                return null;
            });
            assertTrue(admitted.await(5, TimeUnit.SECONDS));

            MockHttpServletResponse overloaded = new MockHttpServletResponse();
            filter.doFilter(
                    new MockHttpServletRequest("POST", "/api/v1/index/bulk"),
                    overloaded,
                    (ignoredRequest, ignoredResponse) -> {});

            assertEquals(429, overloaded.getStatus());
            assertEquals("1", overloaded.getHeader("Retry-After"));
            assertTrue(overloaded.getContentAsString().contains("retry after"));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
