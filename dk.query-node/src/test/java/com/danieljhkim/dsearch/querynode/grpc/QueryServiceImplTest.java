package com.danieljhkim.dsearch.querynode.grpc;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.danieljhkim.dsearch.common.enums.SearchType;
import com.danieljhkim.dsearch.common.model.SearchResult;
import com.danieljhkim.dsearch.proto.query.QueryRequest;
import com.danieljhkim.dsearch.proto.query.QueryResponse;
import com.danieljhkim.dsearch.querynode.search.SearchExecutor;

import io.grpc.stub.StreamObserver;

class QueryServiceImplTest {

	private SearchExecutor mockSearchExecutor;
	private BaseIndexService mockIndexService;
	private StreamObserver<QueryResponse> mockResponseObserver;
	private QueryServiceImpl service;

	@BeforeEach
	void setUp() {
		mockSearchExecutor = mock(SearchExecutor.class);
		mockIndexService = mock(BaseIndexService.class);
		mockResponseObserver = mock(StreamObserver.class);
		service = new QueryServiceImpl(mockSearchExecutor, mockIndexService);
	}

	@Test
	void testSearch_ValidRequest() {
		// Setup
		QueryRequest request = QueryRequest.newBuilder()
				.setQueryString("test query")
				.setPage(0)
				.setSize(10)
				.setPartitionId("default")
				.setSearchType(com.danieljhkim.dsearch.proto.common.SearchType.BM25)
				.build();

		SearchResult mockResult = new SearchResult(new ArrayList<>(), 100);
		when(mockSearchExecutor.search(anyString(), anyString(), anyInt(), anyInt(), any(SearchType.class),
				any(BaseIndexService.class))).thenReturn(mockResult);

		// Execute
		assertDoesNotThrow(() -> service.search(request, mockResponseObserver));

		// Verify
		verify(mockResponseObserver).onNext(any(QueryResponse.class));
		verify(mockResponseObserver).onCompleted();
	}

	@Test
	void testSearch_ExceedsPageSizeLimit() {
		// Setup - validation is now done in RequestLimitsValidator which loads config
		// internally
		QueryRequest request = QueryRequest.newBuilder()
				.setQueryString("test query")
				.setPage(0)
				.setSize(10001) // Exceeds maxSize (default 1000)
				.setPartitionId("default")
				.setSearchType(com.danieljhkim.dsearch.proto.common.SearchType.BM25)
				.build();

		// Execute - exception will be thrown and caught by GlobalExceptionInterceptor
		// in real usage
		// In unit tests without the interceptor, the exception propagates
		assertThrows(IllegalArgumentException.class, () -> service.search(request, mockResponseObserver));
	}

	@Test
	void testSearch_ExceedsQueryLengthLimit() {
		// Setup - validation is now done in RequestLimitsValidator which loads config
		// internally
		QueryRequest request = QueryRequest.newBuilder()
				.setQueryString("a".repeat(2048)) // Exceeds maxQueryLength (default 1024)
				.setPage(0)
				.setSize(10)
				.setPartitionId("default")
				.setSearchType(com.danieljhkim.dsearch.proto.common.SearchType.BM25)
				.build();

		// Execute - exception will be thrown and caught by GlobalExceptionInterceptor
		// in real usage
		// In unit tests without the interceptor, the exception propagates
		assertThrows(IllegalArgumentException.class, () -> service.search(request, mockResponseObserver));
	}
}
