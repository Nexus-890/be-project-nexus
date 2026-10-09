package com.group5.catalogservice.category.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CategoryTreeResponse {

        private UUID id;
        private UUID parentId;
        private String name;
        private String slug;
        private Short level;
        private String path;

        private List<CategoryTreeResponse> children = new ArrayList<>();
}
