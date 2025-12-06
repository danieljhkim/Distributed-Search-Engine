package com.danieljhkim.dsearch.common.enums;

public class EnumMapper {

    public static com.dk.dsearch.proto.common.SearchType mapToProtoEnum(
            SearchType type
    ) {
        if (type == null) {
            return com.dk.dsearch.proto.common.SearchType.SEARCH_TYPE_UNSPECIFIED;
        }
        return switch (type) {
            case BM25 -> com.dk.dsearch.proto.common.SearchType.BM25;
            case SEMANTIC -> com.dk.dsearch.proto.common.SearchType.SEMANTIC;
            case HYBRID -> com.dk.dsearch.proto.common.SearchType.HYBRID;
        };
    }

    public static SearchType mapFromProtoEnum(
            com.dk.dsearch.proto.common.SearchType type
    ) {
        if (type == null || type == com.dk.dsearch.proto.common.SearchType.SEARCH_TYPE_UNSPECIFIED) {
            return SearchType.BM25;
        }
        return switch (type) {
            case SEMANTIC -> SearchType.SEMANTIC;
            case HYBRID -> SearchType.HYBRID;
            default -> SearchType.BM25;
        };
    }
}
