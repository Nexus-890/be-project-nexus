package com.group5.catalogservice.category.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Value
public class CategoryTreeResponse {

        UUID id;
        UUID parentId;
        String name;
        String slug;
        Short level;
        List<CategoryTreeResponse> children;
}
