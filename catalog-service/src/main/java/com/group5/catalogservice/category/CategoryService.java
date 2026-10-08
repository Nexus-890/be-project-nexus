package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryRequest;
import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.validation.SlugValidation;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
                    .findByIdAndDeleteAtIsNull(request.getParentId())
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
                ? "/" + id + "/"
                : parent.getPath() + id + "/";

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
        log.info("Update category: name={},slug={}, description={}", request.getName(),
                request.getSlug(), request.getDescription());

        Category category = categoryRepository
                .findByIdAndDeleteAtIsNull(id)
                .orElseThrow(() -> {

                    log.warn("Category not found: id={}", id);

                    return new DomainException(
                            ErrorCode.CATEGORY_NOT_FOUND );
                });

        String slug = slugValidation.normalizeAndValidate(request.getSlug());

        if (categoryRepository.existsBySlugAndIdNot(slug, id)) {
            log.warn(
                    "Category slug already exists: slug={}, categoryId={}", slug, id
            );

            throw new DomainException(
                    ErrorCode.SLUG_DUPLICATED);
        }

        category.setName(request.getName().trim());
        category.setSlug(slug);
        category.setDescription(request.getDescription());

        Category saved = categoryRepository.saveAndFlush(category);

        log.debug(
                "Prepared category update: id={}, name={}, slug={}",
                id, category.getName(), category.getSlug()
        );

        log.info("Finished updating category: id={}", saved.getId());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CategoryResponse getById(UUID id) {
        log.debug("Getting category: id={}", id);

        Category category = categoryRepository
                .findByIdAndDeleteAtIsNull(id)
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
}
