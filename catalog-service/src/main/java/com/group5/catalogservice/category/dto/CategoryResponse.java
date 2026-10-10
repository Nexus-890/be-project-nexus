package com.group5.catalogservice.category.dto;

import lombok.*;

import java.util.UUID;


@Value
public class CategoryResponse {

    UUID id;
    UUID parentId;
    String name;
    String slug;
    Short level;
    String path;
}
