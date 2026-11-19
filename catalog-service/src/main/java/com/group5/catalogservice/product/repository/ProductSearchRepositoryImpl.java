package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Apply search and ordering in PostgreSQL before slicing the result page. */
public class ProductSearchRepositoryImpl implements ProductSearchRepository {
    @PersistenceContext private EntityManager entityManager;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductSearchRepositoryImpl(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Page<ProductResponse> searchFullText(UUID categoryId, String keyword, String sort, Boolean auctionEnabled,
                                                BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        var query = FullTextProductQuery.of(categoryId, keyword, sort, auctionEnabled, minPrice, maxPrice, pageable);
        var content = jdbc.query(query.content(), query.parameters(), (row, number) -> new ProductResponse(
                row.getObject("id", UUID.class), row.getString("name"), row.getString("slug"), ProductStatus.ACTIVE,
                row.getObject("category_id", UUID.class), row.getObject("seller_id", UUID.class),
                row.getBigDecimal("min_price"), row.getBigDecimal("max_price")));
        long count = jdbc.queryForObject(query.count(), query.parameters(), Long.class);
        return new PageImpl<>(content, pageable, count);
    }

    @Override
    public List<ProductResponse> discover(boolean popular, int limit) {
        String sql = """
                SELECT p.id, p.name, p.slug, p.category_id, p.seller_id, price.min_price, price.max_price
                FROM products p
                CROSS JOIN LATERAL (
                    SELECT min(s.price) AS min_price, max(s.price) AS max_price
                    FROM skus s WHERE s.product_id = p.id AND s.status = 'ACTIVE'
                ) price
                """ + (popular ? "JOIN product_stats stats ON stats.product_id = p.id AND stats.order_count > 0\n" : "")
                + """
                WHERE p.status = 'ACTIVE' AND p.deleted_at IS NULL AND price.min_price IS NOT NULL
                ORDER BY """ + (popular ? " stats.order_count DESC," : "") + " p.created_at DESC, p.id LIMIT :limit";
        return jdbc.query(sql, Map.of("limit", limit), (row, number) -> new ProductResponse(
                row.getObject("id", UUID.class), row.getString("name"), row.getString("slug"), ProductStatus.ACTIVE,
                row.getObject("category_id", UUID.class), row.getObject("seller_id", UUID.class),
                row.getBigDecimal("min_price"), row.getBigDecimal("max_price")));
    }

    @Override
    public Page<ProductResponse> searchVisibleProducts(UUID categoryId, String keyword, String sort,
                                                       Boolean auctionEnabled, BigDecimal maxPrice, Pageable pageable) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createQuery(ProductResponse.class);
        var product = query.from(Product.class);
        Join<Product, Skus> sku = product.join("skuses", JoinType.LEFT);
        Expression<BigDecimal> minimumPrice = builder.min(sku.get("price"));
        query.select(builder.construct(ProductResponse.class, product.get("id"), product.get("name"),
                product.get("slug"), product.get("status"), product.get("category").get("id"),
                product.get("sellerId"), minimumPrice, builder.max(sku.<BigDecimal>get("price"))));
        query.where(predicates(builder, query, product, categoryId, keyword, auctionEnabled, maxPrice));
        query.groupBy(product.get("id"), product.get("name"), product.get("slug"), product.get("status"),
                product.get("category").get("id"), product.get("sellerId"), product.get("createdAt"));

        List<Order> orders = new ArrayList<>();
        if (sort.startsWith("price,")) {
            orders.add(builder.asc(builder.<Integer>selectCase().when(builder.isNull(minimumPrice), 1).otherwise(0)));
            orders.add(sort.endsWith("asc") ? builder.asc(minimumPrice) : builder.desc(minimumPrice));
        } else {
            if (!keyword.isEmpty() && sort.equals("relevance")) {
                var title = builder.lower(product.<String>get("name"));
                var lower = keyword.toLowerCase(Locale.ROOT);
                orders.add(builder.asc(builder.<Integer>selectCase().when(builder.equal(title, lower), 0)
                        .when(builder.like(title, literalLike(lower) + "%", '\\'), 1).otherwise(2)));
            }
            orders.add(builder.desc(product.get("createdAt")));
        }
        orders.add(builder.asc(product.get("id")));
        query.orderBy(orders);

        var countQuery = builder.createQuery(Long.class);
        var counted = countQuery.from(Product.class);
        countQuery.select(builder.count(counted));
        countQuery.where(predicates(builder, countQuery, counted, categoryId, keyword, auctionEnabled, maxPrice));
        long count = entityManager.createQuery(countQuery).getSingleResult();
        var content = entityManager.createQuery(query).setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize()).getResultList();
        return new PageImpl<>(content, pageable, count);
    }

    private Predicate[] predicates(CriteriaBuilder builder, CommonAbstractCriteria query, Root<Product> product,
                                   UUID categoryId, String keyword, Boolean auctionEnabled, BigDecimal maxPrice) {
        List<Predicate> filters = new ArrayList<>();
        filters.add(builder.equal(product.get("status"), ProductStatus.ACTIVE));
        filters.add(builder.isNull(product.get("deletedAt")));
        if (categoryId != null) filters.add(builder.equal(product.get("category").get("id"), categoryId));
        if (!keyword.isEmpty()) filters.add(builder.like(builder.lower(product.get("name")),
                "%" + literalLike(keyword.toLowerCase(Locale.ROOT)) + "%", '\\'));
        if (auctionEnabled != null) filters.add(builder.equal(product.get("auctionEnable"), auctionEnabled));
        if (maxPrice != null) {
            // A SKU ceiling filters products without truncating their displayed min/max variant range.
            var affordable = query.subquery(Integer.class);
            var sku = affordable.from(Skus.class);
            affordable.select(builder.literal(1)).where(builder.equal(sku.get("product"), product),
                    builder.lessThanOrEqualTo(sku.get("price"), maxPrice));
            filters.add(builder.exists(affordable));
        }
        return filters.toArray(Predicate[]::new);
    }

    private static String literalLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
