package com.danieljhkim.dsearch.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthFilterTest {

    @Test
    void missingTokenConfigurationRefusesAdminRoutes() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(request(null), response, chain);
        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void validBearerTokenSetsActorAndContinues() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("secret");
        MockHttpServletRequest request = request("Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertEquals(AdminAuthFilter.ADMIN_ACTOR, request.getAttribute(AdminAuthFilter.ACTOR_ATTRIBUTE));
        verify(chain).doFilter(request, response);
    }

    @Test
    void wrongTokenIsForbidden() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(request("Bearer other"), response, chain);
        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/indexes");
        request.setRequestURI("/api/v1/admin/indexes");
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request;
    }
}
