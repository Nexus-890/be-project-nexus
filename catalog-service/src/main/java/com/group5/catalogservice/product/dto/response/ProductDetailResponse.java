package com.group5.catalogservice.product.dto.response;
import com.group5.catalogservice.product.enums.ProductStatus;
import lombok.Getter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
public class ProductDetailResponse extends ProductResponse {
    private final String description;
    private final Boolean auctionEnable;
    private final List<SkuResponse> skus;

    public ProductDetailResponse(UUID id, String name, String slug, ProductStatus status,
                                 UUID categoryId, UUID sellerId, String description,
                                 Boolean auctionEnable, List<SkuResponse> skus,
                                 List<ImageResponse> images) {
        super(id, name, slug, status, categoryId, sellerId);
        this.description = description;
        this.auctionEnable = auctionEnable;
        this.skus = List.copyOf(skus);
        setMinPrice(this.skus.stream().map(SkuResponse::getPrice).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null));
        setMaxPrice(this.skus.stream().map(SkuResponse::getPrice).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null));
        setImages(List.copyOf(images));
    }




}
