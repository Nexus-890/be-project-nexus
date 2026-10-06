package com.group5.catalogservice.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CategoryRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    String name;

    @NotBlank(message = "Slug is required")
    @Size(max = 100, message = "Slug must not exceed 100 characters")
    @Pattern(
            regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
            message = "Slug must contain only lowercase letters, numbers and hyphens"
    )
    String slug;

    UUID parentId;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    String description;
}
