package com.group5.catalogservice.category;

import org.springframework.data.jpa.repository.JpaRepository;
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
    List<Category> findByPathStartingWithAndIdNot(String path, UUID id);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM products WHERE category_id = :categoryId)", nativeQuery = true)
    boolean existsProductByCategoryId(@Param("categoryId") UUID categoryId);
}
