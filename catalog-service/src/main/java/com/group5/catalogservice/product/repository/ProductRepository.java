package com.group5.catalogservice.product.repository;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.ProductImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.group5.catalogservice.product.enums.ProductStatus;
import java.util.UUID;
import java.util.Optional;
import java.util.Collection;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID>, ProductSearchRepository {
    @Query("""
            SELECT image FROM ProductImage image WHERE image.product.id IN :productIds
            ORDER BY image.product.id, image.sortOrder, image.id
            """)
    List<ProductImage> findImagesForProducts(@Param("productIds") Collection<UUID> productIds);

    Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

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
}
