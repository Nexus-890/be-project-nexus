package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryRequest;
import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.category.dto.CategoryUpdateRequest;
import com.group5.catalogservice.category.dto.CategoryTreeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAuthority('CATEGORY.CREATE')")
    public CategoryResponse create(
            @Valid @RequestBody CategoryRequest request) {

        return categoryService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY.UPDATE')")
    public CategoryResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryUpdateRequest request) {

        return categoryService.update(id, request);
    }
    @GetMapping("/tree")
    @PreAuthorize("permitAll()")
    public List<CategoryTreeResponse> getCategoryTree() {
        return categoryService.getCategoryTree();
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public CategoryResponse getById(
            @PathVariable UUID id) {

        return categoryService.getById(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY.DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        categoryService.delete(id);
    }
}
