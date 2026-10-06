package com.group5.catalogservice.category.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class CategoryRequest {

        String name;
        String slug;
        UUID parentId;
        String description;
}
