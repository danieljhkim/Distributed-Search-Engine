package com.dk.dsearch.common.shard;

import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Setter
@Getter
public class ShardId {
    private String id;

    public ShardId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "ShardId{" +
                "id='" + id + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ShardId shardId = (ShardId) o;

        return Objects.equals(id, shardId.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}