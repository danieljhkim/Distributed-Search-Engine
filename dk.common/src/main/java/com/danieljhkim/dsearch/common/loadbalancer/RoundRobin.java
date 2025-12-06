package com.danieljhkim.dsearch.common.loadbalancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class RoundRobin<T> {

    private static final Logger LOGGER = Logger.getLogger(RoundRobin.class.getName());
    private final List<T> items;
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoundRobin(List<T> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Item list must not be empty");
        }
        this.items = items;
    }

    public T next() {
        int idx = Math.floorMod(counter.getAndIncrement(), items.size());
        LOGGER.info("round robin selected index: " + idx);
        return items.get(idx);
    }
}