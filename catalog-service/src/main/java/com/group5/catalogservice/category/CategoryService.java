package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryRequest;
import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.category.dto.CategoryTreeResponse;
import com.group5.catalogservice.category.dto.CategoryUpdateRequest;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.validation.SlugValidation;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.OffsetDateTime;
import java.util.Comparator;

import java.text.Collator;

import java.util.Objects;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;

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
    public CategoryResponse update(UUID id, CategoryUpdateRequest request) {
        Category category = categoryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DomainException(ErrorCode.CATEGORY_NOT_FOUND));

        String name = request.hasName() ? request.getName() : category.getName();
        String slug = request.hasSlug() ? request.getSlug() : category.getSlug();
        String description = request.hasDescription() ? request.getDescription() : category.getDescription();
        UUID parentId = request.hasParentId() ? request.getParentId() : category.getParentId();

        name = name.trim();
        boolean slugChanged = !Objects.equals(category.getSlug(), slug);//nếu thay đổi -> True
        boolean parentChanged = !Objects.equals(category.getParentId(), parentId);//nếu thay đổi -> True
        if (slugChanged && categoryRepository.existsBySlugAndIdNot(slug, id)) {
            throw new DomainException(ErrorCode.SLUG_DUPLICATED);//này so sánh slug mới có trùng slug khác trong hệ thống không ( né ID chính nó rùi )
        }

        //nếu 1 trong 2 change
        if (slugChanged || parentChanged) {
            String oldPath = category.getPath();
            Category parent = null;
            if (parentId != null) {
                if (parentId.equals(id)) {//k làm con chính nó
                    throw new DomainException(ErrorCode.INVALID_PARENT);
                }
                parent = categoryRepository.findByIdAndDeletedAtIsNull(parentId)//tìm Id cha xem có tồn tại k
                        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
                if (parent.getPath().startsWith(oldPath)) {//kiểm tra xem có chuyển trong nhánh đó không ( dựa theo path cũ )
                    throw new DomainException(ErrorCode.DATA_INVALID);
                }
            }

            int newLevel = parent == null ? 1 : parent.getLevel() + 1;//này set lại level //lv2 -> lv1----     lv3 + -1 = 2
            //-1
            int levelDelta = newLevel - category.getLevel();//level lệch lúc old và lúc new để thay đổi level của con
            String newPath = parent == null ? "/" + slug + "/" : parent.getPath() + slug + "/";//này đổi path chính nó
            validateTreePosition(newLevel, newPath);
            //
            List<Category> descendants = categoryRepository.findByPathStartingWithAndIdNot(oldPath, id);

            for (Category descendant : descendants) {
                String descendantPath = newPath + descendant.getPath().substring(oldPath.length());//cái substring
                //khối này để validate thôi
                // sẽ lấy cái oldPath(cái path của cate đang đổi)
                /*
                * Path của Apple: /dien-tu/dien-thoai/apple/
                oldPath:        /dien-tu/dien-thoai/
                Phần còn lại:   apple/  -> biến thành newPath + phần còn lại
                    /smartphone/ + apple/
                    = /smartphone/apple/
                *
                */
                validateTreePosition(descendant.getLevel() + levelDelta, descendantPath);
            }

            for (Category descendant : descendants) {
                //này set vô
                descendant.setPath(newPath + descendant.getPath().substring(oldPath.length()));
                descendant.setLevel((short) (descendant.getLevel() + levelDelta));
            }
            category.setParentId(parentId);
            category.setLevel((short) newLevel);
            category.setPath(newPath);
        }

        category.setName(name);
        category.setSlug(slug);
        category.setDescription(description);

        Category saved = categoryRepository.saveAndFlush(category);

        log.info("Finished updating category: id={}", saved.getId());

        return toResponse(saved);
    }
    private void validateTreePosition(int level, String path) {
        if (level > catalogProperties.getCategoryMaxLevel()) {
            throw new DomainException(ErrorCode.CATEGORY_LEVEL_MAX);
        }
        if (path.length() > 500) {
            throw new DomainException(ErrorCode.DATA_INVALID);
        }
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



    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> getCategoryTree() {
        // 1. Load hết category trong một lần SELECT
        List<Category> categories = categoryRepository.findAllByDeletedAtIsNull();

        if (categories.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Tạo map nhanh để dò các parent theo id
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

        // 4. Tránh lặp lại quan hệ cha - con
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

        // 6. Sort nhánh cha và mọi nhánh con
        sortCategoryTree(roots);

        return roots;
    }


    private void sortCategoryTree(List<CategoryTreeResponse> categories) {
        CategorySortType sortType =
                catalogProperties.getCategoryTree().getSortType();

        Comparator<CategoryTreeResponse> comparator;

        switch (sortType) {
            case NAME_DESC: {
                Collator collator = Collator.getInstance(
                        Locale.forLanguageTag("vi")
                );
                collator.setStrength(Collator.PRIMARY);

                comparator = Comparator.comparing(
                        CategoryTreeResponse::getName,
                        collator
                ).reversed();
                break;
            }

            case SLUG_ASC:
                comparator = Comparator.comparing(
                        CategoryTreeResponse::getSlug
                );
                break;

            case SLUG_DESC:
                comparator = Comparator.comparing(
                        CategoryTreeResponse::getSlug
                ).reversed();
                break;

            case NAME_ASC:
            default: {
                Collator collator = Collator.getInstance(
                        Locale.forLanguageTag("vi")
                );
                collator.setStrength(Collator.PRIMARY);

                comparator = Comparator.comparing(
                        CategoryTreeResponse::getName, collator
                );
                break;
            }
        }

        categories.sort(comparator);

        for (CategoryTreeResponse category : categories) {
            sortCategoryTree(category.getChildren());
        }
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

    @Transactional
    public void delete(UUID id) {
        Category category = categoryRepository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() ->
                        new DomainException(ErrorCode.CATEGORY_NOT_FOUND)
                );

        if (categoryRepository.existsByParentIdAndDeletedAtIsNull(id)) {
            throw new DomainException(ErrorCode.CATEGORY_HAS_CHILDREN);
        }

        boolean hasProducts =
                categoryRepository.existsProductByCategoryId(id);

        if (hasProducts) {
            if (!catalogProperties.isAllowCategoryDeleteWithProduct()) {
                throw new DomainException(ErrorCode.CATEGORY_HAS_PRODUCTS);
            }

            // Xóa mềm category tại đây.
            // Giữ nguyên products.category_id để không phá khóa ngoại.
            category.setDeletedAt(OffsetDateTime.now());
            categoryRepository.save(category);
            return;
        }

        categoryRepository.delete(category);
        categoryRepository.flush();
    }
}
