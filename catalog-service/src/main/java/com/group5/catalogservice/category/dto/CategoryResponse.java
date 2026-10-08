package com.group5.catalogservice.category.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponse{
        UUID id;
        UUID parentId;
        String name;
        String slug;
        Short level;
        String path;
}
