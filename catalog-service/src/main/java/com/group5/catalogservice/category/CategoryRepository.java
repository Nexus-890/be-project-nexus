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

    String FLAT_FILTER = """
            FROM categories c WHERE c.deleted_at IS NULL
              AND (CAST(:parentId AS uuid) IS NULL OR c.parent_id = :parentId)
              AND (CAST(:level AS smallint) IS NULL OR c.level = :level)
              AND (lower(public.unaccent(c.name)) LIKE lower(public.unaccent(:pattern)) ESCAPE '!'
                   OR lower(public.unaccent(c.slug)) LIKE lower(public.unaccent(:pattern)) ESCAPE '!')
            """;

    @Query(value = "SELECT c.* " + FLAT_FILTER, countQuery = "SELECT count(*) " + FLAT_FILTER, nativeQuery = true)
    Page<Category> findFlat(String pattern, UUID parentId, Short level, Pageable pageable);

    List<Category> findByPathStartingWithAndIdNot(String path, UUID id);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM products WHERE category_id = :categoryId)", nativeQuery = true)
    boolean existsProductByCategoryId(@Param("categoryId") UUID categoryId);
}
