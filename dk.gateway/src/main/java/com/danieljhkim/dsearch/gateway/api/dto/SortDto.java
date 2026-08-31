package com.danieljhkim.dsearch.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * One ordering component of a search request.
 *
 * <p>{@code field} is a sortable field name, or one of the pseudo-fields {@code _score} and
 * {@code _id}. {@code order} is {@code asc} or {@code desc}, defaulting to ascending.
 */
@Setter
@Getter
public class SortDto {

    @NotBlank(message = "sort field must not be blank") private String field;

    @Pattern(regexp = "(?i)asc|desc", message = "sort order must be 'asc' or 'desc'")
    private String order = "asc";

    public SortDto() {}

    public SortDto(String field, String order) {
        this.field = field;
        this.order = order;
    }

    public boolean isDescending() {
        return order != null && order.equalsIgnoreCase("desc");
    }
}
