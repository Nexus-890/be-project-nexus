package com.group5.catalogservice.product.repository;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.ProductImage;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.group5.catalogservice.product.enums.ProductStatus;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.Optional;
import java.util.Collection;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID>, ProductSearchRepository {
    String SEARCH_FILTER = """
            FROM products p
            CROSS JOIN LATERAL (
                SELECT min(s.price) AS min_price, max(s.price) AS max_price
                FROM skus s
                WHERE s.product_id = p.id AND s.status = 'ACTIVE'
            ) price
            WHERE p.status = 'ACTIVE' AND p.deleted_at IS NULL
              AND (CAST(:categoryId AS uuid) IS NULL OR p.category_id = :categoryId)
              AND (CAST(:minPrice AS numeric) IS NULL OR price.min_price >= :minPrice)
              AND (CAST(:maxPrice AS numeric) IS NULL OR price.min_price <= :maxPrice)
              AND (:q = '' OR
                   p.name_search @@ phraseto_tsquery('public.catalog_product_search', :q) OR
                   p.description_search @@ phraseto_tsquery('public.catalog_product_search', :q))
            """;

    String SEARCH_QUERY = """
            SELECT p.id, p.name, p.slug, p.category_id AS "categoryId", p.seller_id AS "sellerId",
                   price.min_price AS "minPrice", price.max_price AS "maxPrice"
            """ + SEARCH_FILTER + """
            ORDER BY
              CASE WHEN :sort = 'relevance' AND :q <> '' THEN
                    2 * ts_rank_cd(p.name_search, phraseto_tsquery('public.catalog_product_search', :q)) +
                        ts_rank_cd(p.description_search, phraseto_tsquery('public.catalog_product_search', :q))
              END DESC NULLS LAST,
              CASE WHEN :sort = 'price_asc' THEN price.min_price END ASC NULLS LAST,
              CASE WHEN :sort = 'price_desc' THEN price.min_price END DESC NULLS LAST,
              p.created_at DESC, p.id
            """;

    @Query(value = SEARCH_QUERY, countQuery = "SELECT count(*) " + SEARCH_FILTER, nativeQuery = true)
    Page<SearchRow> search(@Param("q") String q, @Param("categoryId") UUID categoryId,
                           @Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice,
                           @Param("sort") String sort, Pageable pageable);

    interface SearchRow {
        UUID getId();
        String getName();
        String getSlug();
        UUID getCategoryId();
        UUID getSellerId();
        BigDecimal getMinPrice();
        BigDecimal getMaxPrice();
    }

    @Query("""
            SELECT image FROM ProductImage image WHERE image.product.id IN :productIds
            ORDER BY image.product.id, image.sortOrder, image.id
            """)
    List<ProductImage> findImagesForProducts(@Param("productIds") Collection<UUID> productIds);

    Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Product> findForDeleteByIdAndDeletedAtIsNull(UUID id);

    /**
     * Product Detail.
     * Fetch Product, Category, SKUs và Images cùng lúc.
     */
    @EntityGraph(attributePaths = {
            "category",
            "skuses",
            "productImages"
    })
    Optional<Product> findByIdAndStatusAndDeletedAtIsNull(
            UUID id,
            ProductStatus status
    );

    /**
     * Product List không filter category.
     * LEFT JOIN giữ lại Product không có SKU.
     * MIN(s.price) và MAX(s.price) sẽ trả null nếu Product không có SKU.
     */
    @Query(
            value = """
                    select new com.group5.catalogservice.product.dto.response.ProductResponse(
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId,
                        min(s.price),
                        max(s.price)
                    )
                    from Product p
                    left join p.skuses s
                    where p.status = :status
                      and p.deletedAt is null
                    group by
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId
                    """,
            countQuery = """
                    select count(p)
                    from Product p
                    where p.status = :status
                      and p.deletedAt is null
                    """
    )
    Page<ProductResponse> findProductResponsesByStatus(
            @Param("status") ProductStatus status,
            Pageable pageable
    );

    /**
     * Product List có filter category.
     */
    @Query(
            value = """
                    select new com.group5.catalogservice.product.dto.response.ProductResponse(
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId,
                        min(s.price),
                        max(s.price)
                    )
                    from Product p
                    left join p.skuses s
                    where p.category.id = :categoryId
                      and p.status = :status
                      and p.deletedAt is null
                    group by
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId
                    """,
            countQuery = """
                    select count(p)
                    from Product p
                    where p.category.id = :categoryId
                      and p.status = :status
                      and p.deletedAt is null
                    """
    )
    Page<ProductResponse> findProductResponsesByCategory(
            @Param("categoryId") UUID categoryId,
            @Param("status") ProductStatus status,
            Pageable pageable
    );

    /**
     * Product List của Seller.
     */
    @Query(
            value = """
                    select new com.group5.catalogservice.product.dto.response.ProductResponse(
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId,
                        min(s.price),
                        max(s.price)
                    )
                    from Product p
                    left join p.skuses s
                    where p.sellerId = :sellerId
                      and p.deletedAt is null
                    group by
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId
                    """,
            countQuery = """
                    select count(p)
                    from Product p
                    where p.sellerId = :sellerId
                      and p.deletedAt is null
                    """
    )
    Page<ProductResponse> findProductResponsesBySeller(
            @Param("sellerId") UUID sellerId,
            Pageable pageable
    );

    @Query(
            value = """
                    select new com.group5.catalogservice.product.dto.response.ProductResponse(
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId,
                        min(s.price),
                        max(s.price)
                    )
                    from Product p
                    left join p.skuses s
                    where p.sellerId = :sellerId
                      and p.status = :status
                      and p.deletedAt is null
                    group by
                        p.id,
                        p.name,
                        p.slug,
                        p.status,
                        p.category.id,
                        p.sellerId
                    """,
            countQuery = """
                    select count(p)
                    from Product p
                    where p.sellerId = :sellerId
                      and p.status = :status
                      and p.deletedAt is null
                    """
    )
    Page<ProductResponse> findProductResponsesBySellerAndStatus(
            @Param("sellerId") UUID sellerId,
            @Param("status") ProductStatus status,
            Pageable pageable
    );
}
