package com.dk.dsearch.common.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Filter {
    private String field;
    private String value;

    public Filter() {
    }

    public Filter(String field, String value) {
        this.field = field;
        this.value = value;
    }

    @Override
    public String toString() {
        return "Filter{" +
                "field='" + field + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}