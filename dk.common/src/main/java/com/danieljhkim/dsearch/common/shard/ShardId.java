package com.danieljhkim.dsearch.common.shard;

import java.util.Objects;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ShardId {
	private String id;

	public ShardId(String id) {
		this.id = id;
	}

	public ShardId(String nodeId, String partitionId) {
		this.id = partitionId + "_" + nodeId;
	}

	@Override
	public String toString() {
		return "ShardId{" +
				"id='" + id + '\'' +
				'}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;

		ShardId shardId = (ShardId) o;

		return Objects.equals(id, shardId.id);
	}

	@Override
	public int hashCode() {
		return id != null ? id.hashCode() : 0;
	}
}