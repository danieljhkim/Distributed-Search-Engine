package com.danieljhkim.dsearch.common.loadbalancer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.danieljhkim.dsearch.common.grpc.NodeClient;

import io.grpc.ManagedChannel;

class RoundRobinTest {

	private List<NodeClient<String>> createMockClients(int count) {
		List<NodeClient<String>> clients = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			// Use mocks instead of real gRPC channels to avoid transport dependency
			ManagedChannel mockChannel = mock(ManagedChannel.class);
			String stub = "stub-" + i;
			NodeClient<String> client = new NodeClient<>("node-" + i, stub, mockChannel, "localhost", 8080 + i);
			// isActive defaults to true, so no need to set it
			clients.add(client);
		}
		return clients;
	}

	@Test
	void testRoundRobin_EmptyList() {
		assertThrows(IllegalArgumentException.class, () -> new RoundRobin<>(new ArrayList<>()));
	}

	@Test
	void testRoundRobin_NullList() {
		assertThrows(IllegalArgumentException.class, () -> new RoundRobin<>(null));
	}

	@Test
	void testNext_SingleItem() {
		List<NodeClient<String>> clients = createMockClients(1);
		RoundRobin<String> rr = new RoundRobin<>(clients);
		NodeClient<String> client = rr.next();
		assertNotNull(client);
	}

	@Test
	void testNext_MultipleItems() {
		List<NodeClient<String>> clients = createMockClients(3);
		RoundRobin<String> rr = new RoundRobin<>(clients);

		NodeClient<String> client1 = rr.next();
		assertNotNull(client1);

		NodeClient<String> client2 = rr.next();
		assertNotNull(client2);

		NodeClient<String> client3 = rr.next();
		assertNotNull(client3);
	}

	@Test
	void testNext_WrapsAround() {
		List<NodeClient<String>> clients = createMockClients(2);
		RoundRobin<String> rr = new RoundRobin<>(clients);

		NodeClient<String> client1 = rr.next();
		NodeClient<String> client2 = rr.next();
		NodeClient<String> client3 = rr.next(); // Should wrap around

		assertNotNull(client1);
		assertNotNull(client2);
		assertNotNull(client3);
	}
}
