package com.danieljhkim.dsearch.common.loadbalancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.danieljhkim.dsearch.common.grpc.NodeClient;

public class RoundRobin<T> {

	private static final Logger LOGGER = Logger.getLogger(RoundRobin.class.getName());
	private final List<NodeClient<T>> items;
	private final AtomicInteger counter = new AtomicInteger(0);

	public RoundRobin(List<NodeClient<T>> items) {
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("Item list must not be empty");
		}
		this.items = items;
	}

	public NodeClient<T> next() {
		for (int i = 0; i < items.size(); i++) {
			int idx = Math.floorMod(counter.getAndIncrement(), items.size());
			if (items.get(idx).isActive()) {
				LOGGER.info("round robin selected index: " + idx);
				return items.get(idx);
			}
		}
		throw new IllegalStateException("No active items available in RoundRobin");
	}

}