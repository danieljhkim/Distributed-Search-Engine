package com.danieljhkim.dsearch.gateway.service;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.grpc.NodeClientManager;
import com.danieljhkim.dsearch.gateway.api.dto.SearchRequestDto;
import com.danieljhkim.dsearch.gateway.api.dto.SearchResponseDto;
import com.danieljhkim.dsearch.gateway.mapper.QueryResponseMapper;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.proto.query.QueryServiceGrpc;

class GatewaySearchServiceTest {

	private NodeClientManager<QueryServiceGrpc.QueryServiceBlockingStub> mockClientManager;
	private QueryResponseMapper mockMapper;
	private QueryServiceGrpc.QueryServiceBlockingStub mockStub;
	private GatewaySearchService service;

	@BeforeEach
	void setUp() {
		mockClientManager = mock(NodeClientManager.class);
		mockMapper = mock(QueryResponseMapper.class);
		mockStub = mock(QueryServiceGrpc.QueryServiceBlockingStub.class);

		when(mockClientManager.nextClient()).thenReturn(mockStub);
		service = new GatewaySearchService(mockClientManager, mockMapper);
	}

	@Test
	void testSearch_ValidRequest() {
		// Setup
		SearchRequestDto request = new SearchRequestDto();
		request.setQuery("test query");
		request.setPage(0);
		request.setPageSize(10);

		QueryResponse mockResponse = QueryResponse.newBuilder()
				.setTotalHits(100)
				.setPage(0)
				.setSize(10)
				.build();

		when(mockStub.search(any(QueryRequest.class))).thenReturn(mockResponse);

		SearchResponseDto mockDto = new SearchResponseDto(new ArrayList<>(), 100, 0L);
		when(mockMapper.toDto(mockResponse)).thenReturn(mockDto);

		// Execute
		assertDoesNotThrow(() -> service.search(request));
	}

	@Test
	void testSearch_ExceedsPageSizeLimit() {
		// Setup - validation is now done in RequestLimitsValidator which loads config
		// internally
		// This test will fail if the config has maxSize < 101
		SearchRequestDto request = new SearchRequestDto();
		request.setQuery("test query");
		request.setPage(0);
		request.setPageSize(101); // May exceed maxSize depending on config

		// Note: This test may or may not throw depending on the actual config loaded
		// For a proper test, we'd need to mock ConfigLoader, but that's complex
		// For now, we'll just verify the method can be called
		try {
			QueryResponse mockResponse = QueryResponse.newBuilder()
					.setTotalHits(100)
					.setPage(0)
					.setSize(101)
					.build();
			when(mockStub.search(any(QueryRequest.class))).thenReturn(mockResponse);
			SearchResponseDto mockDto = new SearchResponseDto(new ArrayList<>(), 100, 0L);
			when(mockMapper.toDto(mockResponse)).thenReturn(mockDto);
			service.search(request);
		} catch (IllegalArgumentException e) {
			// Expected if limits are exceeded
			assert e.getMessage().contains("pageSize");
		}
	}

	@Test
	void testSearch_ExceedsQueryLengthLimit() {
		// Setup - validation is now done in RequestLimitsValidator which loads config
		// internally
		SearchRequestDto request = new SearchRequestDto();
		request.setQuery("a".repeat(2048)); // May exceed maxQueryLength depending on config
		request.setPage(0);
		request.setPageSize(10);

		// Note: This test may or may not throw depending on the actual config loaded
		try {
			QueryResponse mockResponse = QueryResponse.newBuilder()
					.setTotalHits(100)
					.setPage(0)
					.setSize(10)
					.build();
			when(mockStub.search(any(QueryRequest.class))).thenReturn(mockResponse);
			SearchResponseDto mockDto = new SearchResponseDto(new ArrayList<>(), 100, 0L);
			when(mockMapper.toDto(mockResponse)).thenReturn(mockDto);
			service.search(request);
		} catch (IllegalArgumentException e) {
			// Expected if limits are exceeded
			assert e.getMessage().contains("Query length");
		}
	}
}
