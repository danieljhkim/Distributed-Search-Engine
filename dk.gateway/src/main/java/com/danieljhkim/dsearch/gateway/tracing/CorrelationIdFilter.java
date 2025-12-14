package com.danieljhkim.dsearch.gateway.tracing;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		try {
			String requestId = request.getHeader(HEADER_NAME);
			if (requestId == null || requestId.isBlank()) {
				requestId = UUID.randomUUID().toString();
			}
			// Put into MDC so all logs in this thread see it
			MDC.put(MDC_KEY, requestId);
			response.setHeader(HEADER_NAME, requestId);
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}
}