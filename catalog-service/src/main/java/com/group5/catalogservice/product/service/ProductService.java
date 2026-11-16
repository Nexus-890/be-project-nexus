package com.group5.catalogservice.product.service;

import com.group5.catalogservice.product.dto.ShippingParcel;

import com.group5.catalogservice.product.dto.request.ChangeStatusRequest;
import com.group5.catalogservice.product.dto.request.ProductRequest;
import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.dto.response.ProductDetailResponse;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.product.entity.Skus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;
import com.group5.catalogservice.product.OrderReferenceChecker;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSlugGenerator productSlugGenerator;
    private final OrderReferenceChecker orderReferenceChecker;
    private final CatalogProperties catalogProperties;

    @Transactional
    public void deleteProduct(UUID id, CurrentUser currentUser) {
        requirePrivilege(currentUser, "PRODUCT.DELETE");
        if (!currentUser.hasRole("SELLER") && !currentUser.hasRole("ADMIN")) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }

        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getSellerId().equals(currentUser.id())) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
        if (!catalogProperties.isAllowProductDeleteWithOrder()
                && orderReferenceChecker.hasOrderReferences(id)) {
            throw new DomainException(CatalogErrorCode.PRODUCT_HAS_ORDERS);
        }

        product.setDeletedAt(OffsetDateTime.now());
        productRepository.save(product);
        log.info("Soft deleted product: id={}, actorId={}", id, currentUser.id());
    }


    @Transactional
    public ProductResponse create(ProductRequest request, CurrentUser currentUser) {
        log.info("Creating new product: name={}", request.getName());
        //tránh guest - check quyền
        requirePrivilege(currentUser, "PRODUCT.CREATE");
        //tìm category theo Id_category truyền xuống
        Category category = categoryRepository
                .findByIdAndDeletedAtIsNull(request.getCategoryId())
                .orElseThrow(() -> new DomainException(CatalogErrorCode.CATEGORY_NOT_FOUND));


        String name = request.getName().trim();

        Product product = new Product();
        product.setCategory(category);
        product.setSellerId(currentUser.id());
        product.setName(name);
        product.setSlug(productSlugGenerator.generate(name, product.getId()));//Slug = name + id
        product.setDescription(request.getDescription());
        product.setStatus(ProductStatus.DRAFT);
        product.setAuctionEnable(Boolean.TRUE.equals(request.getAuctionEnable()));
        //Boolean.TRUE tiện cho việc xử lí lỗi NullPointerException
        //cho sản phẩm chưa có đơn hàng, tiện cho việc xóa mềm
        product.setHasOrder(false);
        //tạo new product chứ ch xác định có đấu giá chưa  nên false lun
        product.setInAuction(false);

        Product saved = productRepository.saveAndFlush(product);
        log.info("Created product: id={}, sellerId={}", saved.getId(), saved.getSellerId());
        return toResponse(saved);
    }



    @Transactional
    public ProductResponse update(UUID product_id, ProductRequest request, CurrentUser currentUser) {
        log.info("Updating product: name={}", request.getName(), currentUser.id());
        Product product = findEditableProduct(product_id, currentUser);
        Category category = categoryRepository.findByIdAndDeletedAtIsNull(request.getCategoryId())
                .orElseThrow(() -> new DomainException(CatalogErrorCode.CATEGORY_NOT_FOUND));


        product.setName(request.getName().trim());
        product.setSlug(productSlugGenerator.generate(product.getName(), product.getId()));
        product.setDescription(request.getDescription());
        product.setCategory(category);
        product.setAuctionEnable(request.getAuctionEnable());

        Product saved = productRepository.saveAndFlush(product);
        log.info("Updated product: id={}, actorId={}", saved.getId(), currentUser.id());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getById(UUID id) {
        Product product = productRepository
                .findByIdAndStatusAndDeletedAtIsNull(id, ProductStatus.ACTIVE)
                .orElseThrow(() ->
                        new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));

        return toDetailResponse(product, false);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getMyProduct(UUID id, CurrentUser currentUser) {
        requirePrivilege(currentUser, "PRODUCT.LIST");
        if (!currentUser.hasRole("SELLER")) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getSellerId().equals(currentUser.id())) {
            throw new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND);
        }
        // The public read remains ACTIVE-only; an owner's editor also needs drafts.
        return toDetailResponse(product, true);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getProductsByCategory(UUID categoryId, int page, int size) {

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size);


        if (categoryId == null) {
            return attachImages(productRepository.findProductResponsesByStatus(
                    ProductStatus.ACTIVE,
                    pageable
            ));
        }

        return attachImages(productRepository.findProductResponsesByCategory(
                categoryId,
                ProductStatus.ACTIVE,
                pageable
        ));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getMyProducts(CurrentUser currentUser, ProductStatus status, int page, int size) {

        requirePrivilege(currentUser, "PRODUCT.LIST");

        UUID sellerId = currentUser.id();

        validatePagination(page, size);

        log.debug("Getting seller products: sellerId={}, status={}, page={}, size={}", sellerId, status, page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<ProductResponse> responses = status == null
                ? productRepository.findProductResponsesBySeller(sellerId, pageable)
                : productRepository.findProductResponsesBySellerAndStatus(sellerId, status, pageable);
        log.debug("Finished getting seller products: sellerId={}, totalElements={}",
                sellerId, responses.getTotalElements());

        return attachImages(responses);
    }

    private Page<ProductResponse> attachImages(Page<ProductResponse> page) {
        if (!page.isEmpty()) {
            Map<UUID, List<ImageResponse>> imagesByProduct = productRepository.findImagesForProducts(
                            page.getContent().stream().map(ProductResponse::getId).toList()).stream()
                    .collect(Collectors.groupingBy(image -> image.getProduct().getId(),
                            Collectors.mapping(image -> new ImageResponse(image.getId(), image.getImageUrl(),
                                    image.getSortOrder()), Collectors.toList())));
            page.forEach(product -> product.setImages(List.copyOf(
                    imagesByProduct.getOrDefault(product.getId(), List.of()))));
        }
        return page;
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1) {
            throw new DomainException(CatalogErrorCode.DATA_INVALID);
        }
    }
    //method này để check xem đún owner product khum
    private Product findEditableProduct(UUID product_id, CurrentUser currentUser) {
        requirePrivilege(currentUser, "PRODUCT.UPDATE");
        Product product = productRepository.findByIdAndDeletedAtIsNull(product_id)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        if (!currentUser.hasRole("ADMIN") && !product.getSellerId().equals(currentUser.id())) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
        return product;
    }
    @Transactional
    public ProductResponse changeStatusProduct(UUID productId, ChangeStatusRequest request, CurrentUser currentUser) {
        //tránh guest
        Product product = findEditableProduct(productId, currentUser);

        ProductStatus previousStatus = product.getStatus();
        ProductStatus targetStatus = request.status();

        if (!previousStatus.canTransitionTo(targetStatus)) {
            throw new DomainException(
                    CatalogErrorCode.INVALID_PRODUCT_STATUS_TRANSITION
            );
        }

        product.setStatus(targetStatus);
        Product saved = productRepository.save(product);

        log.info(
                "Product status change prepared: productId={}, actorId={}, from={}, to={}",
                productId, currentUser.id(), previousStatus, targetStatus
        );

        return toResponse(saved);
    }

    //method check authen autho
    private void requirePrivilege(CurrentUser currentUser, String privilege) {
        if (currentUser == null) {
            throw new DomainException(CatalogErrorCode.UNAUTHENTICATED);
        }

        if (!currentUser.hasPrivilege(privilege)) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
    }
    //product thường
    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
            product.getId(),
            product.getName(),
            product.getSlug(),
            product.getStatus(),
            product.getCategory().getId(),
            product.getSellerId()
        );
    }
    //detail ( cái này lúc click vô từng món xem kĩ ) -sẽ có 1 list lấy ra full product của issue khác
    private ProductDetailResponse toDetailResponse(Product product, boolean owner) {
        var skus = product.getSkuses().stream()
                .sorted(Comparator.comparing(Skus::getId))
                .map(sku -> new SkuResponse(
                        sku.getId(),
                        product.getId(),
                        sku.getSkuCode(),
                        sku.getColor(),
                        sku.getSize(),
                        sku.getPrice(),
                        sku.getStatus(),
                        sku.getShippingWeightGrams() == null ? null : new ShippingParcel(
                                sku.getShippingWeightGrams(), sku.getShippingLengthCm(),
                                sku.getShippingWidthCm(), sku.getShippingHeightCm()),
                        owner ? sku.getCostPrice() : null
                ))
                .toList();

        var images = product.getProductImages().stream()
                .sorted(Comparator.comparing(ProductImage::getSortOrder)
                        .thenComparing(ProductImage::getId))
                .map(image -> new ImageResponse(
                        image.getId(),
                        image.getImageUrl(),
                        image.getSortOrder()
                ))
                .toList();

        return new ProductDetailResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getStatus(),
                product.getCategory().getId(),
                product.getSellerId(),
                product.getDescription(),
                product.getAuctionEnable(),
                skus,
                images
        );
    }

}
