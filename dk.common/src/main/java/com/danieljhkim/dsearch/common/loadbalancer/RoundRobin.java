package com.danieljhkim.dsearch.common.loadbalancer;

import com.danieljhkim.dsearch.common.grpc.NodeClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class RoundRobin<T> {

    private static final Logger LOGGER = Logger.getLogger(RoundRobin.class.getName());
    private final Supplier<List<NodeClient<T>>> itemsSupplier;
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoundRobin(List<NodeClient<T>> items) {
        validateConfiguredItems(items);
        this.itemsSupplier = () -> items;
    }

    private RoundRobin(Supplier<List<NodeClient<T>>> itemsSupplier) {
        this.itemsSupplier = Objects.requireNonNull(itemsSupplier, "itemsSupplier must not be null");
    }

    public static <T> RoundRobin<T> dynamic(Supplier<List<NodeClient<T>>> itemsSupplier) {
        return new RoundRobin<>(itemsSupplier);
    }

    private static <T> void validateConfiguredItems(List<NodeClient<T>> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Item list must not be empty");
        }
    }

    public NodeClient<T> next() {
        List<NodeClient<T>> items = itemsSupplier.get();
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("No active items available in RoundRobin");
        }
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
