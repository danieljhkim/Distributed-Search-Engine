package com.danieljhkim.dsearch.common.enums;

import com.danieljhkim.dsearch.proto.common.FusionStrategy;

public class EnumMapper {

	public static com.danieljhkim.dsearch.proto.common.SearchType mapToProtoEnum(
			SearchType type) {
		if (type == null) {
			return com.danieljhkim.dsearch.proto.common.SearchType.SEARCH_TYPE_UNSPECIFIED;
		}
		return switch (type) {
			case BM25 -> com.danieljhkim.dsearch.proto.common.SearchType.BM25;
			case SEMANTIC -> com.danieljhkim.dsearch.proto.common.SearchType.SEMANTIC;
			case HYBRID -> com.danieljhkim.dsearch.proto.common.SearchType.HYBRID;
		};
	}

	public static SearchType mapFromProtoEnum(
			com.danieljhkim.dsearch.proto.common.SearchType type) {
		if (type == null || type == com.danieljhkim.dsearch.proto.common.SearchType.SEARCH_TYPE_UNSPECIFIED) {
			return SearchType.BM25;
		}
		return switch (type) {
			case SEMANTIC -> SearchType.SEMANTIC;
			case HYBRID -> SearchType.HYBRID;
			default -> SearchType.BM25;
		};
	}

	public static FusionStrategy mapToProtoEnum(HybridFusionStrategy type) {
		if (type == null) {
			return FusionStrategy.FUSION_TYPE_UNSPECIFIED;
		}
		return switch (type) {
			case SCORE_SUM -> FusionStrategy.SCORE_SUM;
			case WEIGHTED -> FusionStrategy.WEIGHTED;
			case RRF -> FusionStrategy.RRF;
		};
	}

	public static HybridFusionStrategy mapFromProtoEnum(FusionStrategy type) {
		if (type == null || type == FusionStrategy.FUSION_TYPE_UNSPECIFIED) {
			return HybridFusionStrategy.RRF;
		}
		return switch (type) {
			case SCORE_SUM -> HybridFusionStrategy.SCORE_SUM;
			case WEIGHTED -> HybridFusionStrategy.WEIGHTED;
			default -> HybridFusionStrategy.RRF;
		};
	}
}
