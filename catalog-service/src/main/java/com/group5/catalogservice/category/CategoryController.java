package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryRequest;
import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.category.dto.CategoryUpdateRequest;
import com.group5.catalogservice.category.dto.CategoryTreeResponse;
import com.group5.common_core.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CategoryResponse> create(
            @Valid @RequestBody CategoryRequest request) {

        return ApiResponse.of(categoryService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<CategoryResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryUpdateRequest request) {

        return ApiResponse.of(categoryService.update(id, request));
    }
    @GetMapping("/tree")
    public ApiResponse<List<CategoryTreeResponse>> getCategoryTree() {
        return ApiResponse.list(categoryService.getCategoryTree());
    }

    @GetMapping("/{id}")
    public ApiResponse<CategoryResponse> getById(
            @PathVariable UUID id) {

        return ApiResponse.of(categoryService.getById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        categoryService.delete(id);
    }
}
