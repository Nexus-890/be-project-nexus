package com.group5.catalogservice.category.dto;

import lombok.*;

import java.util.UUID;


@Value
public class CategoryResponse {

    private UUID id;
    private UUID parentId;
    private String name;
    private String slug;
    private Short level;
    private String path;
}
