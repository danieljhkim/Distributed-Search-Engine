
package com.dk.search.common.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Field {
    private String name;
    private String value;

    public Field(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String toString() {
        return "Field{" +
                "name='" + name + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}