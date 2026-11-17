package com.group5.catalogservice.product.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.math.BigDecimal;
import java.util.UUID;

/** Build the same parameterized filters for the result page and its total. */
public record FullTextProductQuery(String content, String count, MapSqlParameterSource parameters) {
    public static FullTextProductQuery of(UUID categoryId, String keyword, String sort, Boolean auctionEnabled,
                                         BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        var parameters = new MapSqlParameterSource();
        var filter = new StringBuilder("""
                FROM products p
                CROSS JOIN LATERAL (
                    SELECT min(s.price) AS min_price, max(s.price) AS max_price
                    FROM skus s WHERE s.product_id = p.id AND s.status = 'ACTIVE'
                ) price
                WHERE p.status = 'ACTIVE' AND p.deleted_at IS NULL
                """);
        if (categoryId != null) {
            filter.append(" AND p.category_id = :categoryId");
            parameters.addValue("categoryId", categoryId);
        }
        if (auctionEnabled != null) {
            filter.append(" AND p.auction_enable = :auctionEnabled");
            parameters.addValue("auctionEnabled", auctionEnabled);
        }
        if (!keyword.isEmpty()) {
            filter.append("""
                     AND (p.name_search @@ phraseto_tsquery('public.catalog_product_search', :q)
                          OR p.description_search @@ phraseto_tsquery('public.catalog_product_search', :q))
                    """);
            parameters.addValue("q", keyword);
        }
        if (minPrice != null || maxPrice != null) {
            filter.append(" AND EXISTS (SELECT 1 FROM skus affordable"
                    + " WHERE affordable.product_id = p.id AND affordable.status = 'ACTIVE'");
            if (minPrice != null) {
                filter.append(" AND affordable.price >= :minPrice");
                parameters.addValue("minPrice", minPrice);
            }
            if (maxPrice != null) {
                filter.append(" AND affordable.price <= :maxPrice");
                parameters.addValue("maxPrice", maxPrice);
            }
            filter.append(")");
        }
        String order = switch (sort) {
            case "price_asc" -> "price.min_price ASC NULLS LAST, ";
            case "price_desc" -> "price.min_price DESC NULLS LAST, ";
            case "relevance" -> keyword.isEmpty() ? "" : """
                    (2 * ts_rank_cd(p.name_search, phraseto_tsquery('public.catalog_product_search', :q))
                       + ts_rank_cd(p.description_search, phraseto_tsquery('public.catalog_product_search', :q))) DESC,
                    """;
            case "newest" -> "";
            default -> throw new IllegalArgumentException("Unsupported full-text search sort");
        };
        parameters.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        return new FullTextProductQuery("""
                SELECT p.id, p.name, p.slug, p.category_id, p.seller_id, price.min_price, price.max_price
                """ + filter + " ORDER BY " + order + "p.created_at DESC, p.id LIMIT :limit OFFSET :offset",
                "SELECT count(*) " + filter, parameters);
    }
}
