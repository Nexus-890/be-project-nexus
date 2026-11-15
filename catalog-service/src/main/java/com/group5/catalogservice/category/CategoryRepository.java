package com.group5.catalogservice.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findByIdAndDeletedAtIsNull(UUID id);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, UUID id);
    boolean existsByParentIdAndDeletedAtIsNull(UUID parentId);
    List<Category> findAllByDeletedAtIsNull();

    @Query("""
            SELECT c FROM Category c
            WHERE c.deletedAt IS NULL
              AND (:parentId IS NULL OR c.parentId = :parentId)
              AND (:level IS NULL OR c.level = :level)
              AND (lower(c.name) LIKE :pattern ESCAPE '!' OR lower(c.slug) LIKE :pattern ESCAPE '!')
            """)
    Page<Category> findFlat(String pattern, UUID parentId, Short level, Pageable pageable);

    List<Category> findByPathStartingWithAndIdNot(String path, UUID id);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM products WHERE category_id = :categoryId)", nativeQuery = true)
    boolean existsProductByCategoryId(@Param("categoryId") UUID categoryId);
}
