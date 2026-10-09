package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryRequest;
import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.category.dto.CategoryTreeResponse;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.validation.SlugValidation;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CatalogProperties catalogProperties;
    private final SlugValidation slugValidation;

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        log.info("Start creating categoty: name={}, slug={}", request.getName(), request.getSlug());

        String slug = slugValidation.normalizeAndValidate(request.getSlug());

        if (categoryRepository.existsBySlug(slug)) {
            log.warn("Slug is exists: slug={}", slug);
            throw new DomainException(
                    ErrorCode.SLUG_DUPLICATED);
        }

        Category parent = null;
        short level = 1;

        if (request.getParentId() != null) {
            parent = categoryRepository
                    .findByIdAndDeletedAtIsNull(request.getParentId())
                    .orElseThrow(() -> new DomainException(
                            ErrorCode.NOT_FOUND));

            level = (short) ((short) parent.getLevel() + 1);

        }

        if (level > catalogProperties.getCategoryMaxLevel()) {
            log.warn(
                    "Category level exceeds maximum: level={}, maxLevel={}, parentId={}",
                    level,
                    catalogProperties.getCategoryMaxLevel(),
                    request.getParentId()
            );

            throw new DomainException(ErrorCode.CATEGORY_LEVEL_MAX);
        }

// Tạo category ở ngoài if
        UUID id = UUID.randomUUID();

        Category category = new Category();
        category.setId(id);
        category.setName(request.getName().trim());
        category.setSlug(slug);
        category.setDescription(request.getDescription());
        category.setParentId(request.getParentId());
        category.setLevel(level);
        category.setProductCount(0);

        String path = parent == null
                ? "/" + slug + "/"
                : parent.getPath() + slug + "/";

        category.setPath(path);

        log.debug(
                "Prepared category: id={}, name={}, slug={}, parentId={}, level={}, path={}",
                id,
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                category.getLevel(),
                category.getPath()
        );

        Category saved = categoryRepository.saveAndFlush(category);

        log.info("Finished creating category: id={}", saved.getId());

        return toResponse(saved);
    }

    @Transactional
    public CategoryResponse update(
            UUID id,
            CategoryRequest request) {

        log.info(
                "Update category: name={}, slug={}, description={}, parentId={}",
                request.getName(),
                request.getSlug(),
                request.getDescription(),
                request.getParentId()
        );

        Category category = categoryRepository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> {

                    log.warn("Category not found: id={}", id);

                    return new DomainException(
                            ErrorCode.CATEGORY_NOT_FOUND
                    );
                });

        String slug = slugValidation.normalizeAndValidate(
                request.getSlug()
        );

        if (!category.getSlug().equals(slug)) {
            if (categoryRepository.existsBySlugAndIdNot(slug, id)) {

                log.warn(
                        "Category slug already exists: slug={}, categoryId={}", slug, id);

                throw new DomainException(
                        ErrorCode.SLUG_DUPLICATED
                );
            }
        }


        // Check changing parent
        UUID oldParentId = category.getParentId();
        UUID newParentId = request.getParentId();

        if (!Objects.equals(oldParentId, newParentId)) {

            // Category has children -> do not allow changing parent
            if (categoryRepository.existsByParentIdAndDeletedAtIsNull(id)) {

                log.warn(
                        "Cannot change parent because category has children: id={}",
                        id
                );

                throw new DomainException(ErrorCode.INVALID_CHANGE_PARENT);
            }
        }

        Category parent = null;
        short level = 1;

        if (newParentId != null) {

            if (newParentId.equals(id)) {

                throw new DomainException(ErrorCode.INVALID_PARENT);
            }

            parent = categoryRepository
                    .findByIdAndDeletedAtIsNull(newParentId)
                    .orElseThrow(() -> {

                        log.warn(
                                "Parent category not found: parentId={}",
                                newParentId
                        );

                        return new DomainException(ErrorCode.NOT_FOUND);
                    });

            level = (short) (parent.getLevel() + 1);
        }

        if (level > catalogProperties.getCategoryMaxLevel()) {

            throw new DomainException(ErrorCode.CATEGORY_LEVEL_MAX);
        }

        category.setName(request.getName().trim());
        category.setSlug(slug);
        category.setDescription(request.getDescription());

        // Update parent
        category.setParentId(newParentId);

        // Update level
        category.setLevel(level);

        // Update path
        String path = parent == null
                ? "/" + category.getSlug() + "/"
                : parent.getPath() + category.getSlug() + "/";

        category.setPath(path);

        Category saved = categoryRepository.saveAndFlush(category);

        log.info(
                "Finished updating category: id={}, parentId={}, level={}, path={}",
                saved.getId(),
                saved.getParentId(),
                saved.getLevel(),
                saved.getPath()
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CategoryResponse getById(UUID id) {
        log.debug("Getting category: id={}", id);

        Category category = categoryRepository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> {
                    log.warn("Category not found: id={}", id);

                    return new DomainException(
                            ErrorCode.CATEGORY_NOT_FOUND);
                });

        log.debug(
                "Category found: id={}, name={}, slug={}",
                category.getId(),
                category.getName(),
                category.getSlug()
        );

        return toResponse(category);
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getParentId(),
                category.getName(),
                category.getSlug(),
                category.getLevel(),
                category.getPath()
        );
    }

    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> getCategoryTree() {
        // 1. Load hết category trong một lần SELECT
        List<Category> categories = categoryRepository.findAllByDeletedAtIsNull();

        if (categories.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Tạo map nhanh để dò các parent
        Map<UUID, Category> categoryById = new HashMap<>();
        Map<UUID, CategoryTreeResponse> responseMap = new HashMap<>();

        for (Category category : categories) {
            categoryById.put(category.getId(), category);

            CategoryTreeResponse response = new CategoryTreeResponse(
                    category.getId(),
                    category.getParentId(),
                    category.getName(),
                    category.getSlug(),
                    category.getLevel(),
                    category.getPath(),
                    new ArrayList<>()
            );

            responseMap.put(category.getId(), response);
        }
        // 3. Kiểm tra parent
        for (Category category : categories) {
            UUID parentId = category.getParentId();

            if (parentId != null && !categoryById.containsKey(parentId)) {
                log.error("Invalid category parent: categoryId={}, parentId={}", category.getId(), parentId);

                throw new DomainException(
                        ErrorCode.CATEGORY_NOT_FOUND);
            }
        }

        // 4. Tránh lặp lại quan hệ cha -con
        for (Category category : categories) {
            Set<UUID> visited = new HashSet<>();
            Category current = category;

            while (current != null) {
                if (!visited.add(current.getId())) {
                    log.error("Circular category relationship detected: categoryId={}", category.getId());

                    throw new DomainException(ErrorCode.RELATIONSHIP_ERROR);
                }

                UUID parentId = current.getParentId();
                current = parentId == null
                        ? null
                        : categoryById.get(parentId);
            }
        }

        // 5. Tạo tree trong memory
        List<CategoryTreeResponse> roots = new ArrayList<>();

        for (Category category : categories) {
            CategoryTreeResponse current =
                    responseMap.get(category.getId());

            if (category.getParentId() == null) {
                roots.add(current);
            } else {
                CategoryTreeResponse parent =
                        responseMap.get(category.getParentId());

                parent.getChildren().add(current);
            }
        }

        // 6. Sort root categories and every child list
        sortCategoryTree(roots);

        return roots;
    }

    private void sortCategoryTree(
            List<CategoryTreeResponse> categories) {

        categories.sort(
                Comparator.comparing(
                        CategoryTreeResponse:: getName,
                        String.CASE_INSENSITIVE_ORDER
                )
        );

        for (CategoryTreeResponse category : categories) {
            sortCategoryTree(category.getChildren());
        }
    }
}
