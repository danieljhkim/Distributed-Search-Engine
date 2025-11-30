package com.dk.dsearch.common.enums;

public class EnumMapper {

    public static com.dk.dsearch.proto.common.SearchType mapToProtoEnum(
            com.dk.dsearch.common.enums.SearchType type
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

    public static com.dk.dsearch.common.enums.SearchType mapFromProtoEnum(
            com.dk.dsearch.proto.common.SearchType type
    ) {
        if (type == null || type == com.dk.dsearch.proto.common.SearchType.SEARCH_TYPE_UNSPECIFIED) {
            return com.dk.dsearch.common.enums.SearchType.BM25;
        }
        return switch (type) {
            case SEMANTIC -> com.dk.dsearch.common.enums.SearchType.SEMANTIC;
            case HYBRID -> com.dk.dsearch.common.enums.SearchType.HYBRID;
            default -> com.dk.dsearch.common.enums.SearchType.BM25;
        };
    }
}
