package com.group5.catalogservice.product.service;


import com.group5.catalogservice.product.dto.request.ProductRequest;
import com.group5.catalogservice.product.dto.response.ProductDetailResponse;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.product.entity.Skus;

import java.util.Comparator;

import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSlugGenerator productSlugGenerator;


    @Transactional
    public ProductResponse create(ProductRequest request, CurrentUser currentUser) {
        log.info("Creating new product: name={}", request.getName());
        //tránh guest - check quyền
        requirePrivilege(currentUser, "PRODUCT.CREATE");
        //tìm category theo Id_category truyền xuống
        Category category = categoryRepository
                .findByIdAndDeletedAtIsNull(request.getCategoryId())
                .orElseThrow(() -> new DomainException(ErrorCode.CATEGORY_NOT_FOUND));


        String name = request.getName().trim();

        Product product = new Product();
        product.setCategory(category);
        product.setSellerId(currentUser.id());
        product.setName(name);
        product.setSlug(productSlugGenerator.generate(name, product.getId()));//Slug = name + id
        product.setDescription(request.getDescription());
        product.setStatus("DRAFT");
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
                .orElseThrow(() -> new DomainException(ErrorCode.CATEGORY_NOT_FOUND));


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
                .findByIdAndStatusAndDeletedAtIsNull(id, "ACTIVE")
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND));

        return toDetailResponse(product);
    }

    @Transactional
    public Page<ProductResponse> getProductsByCategory(UUID categoryId, int page, int size) {

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<Product> products;

        if (categoryId == null) {
            products = productRepository.findByStatusAndDeletedAtIsNull("ACTIVE", pageable);
        } else {
            products = productRepository.findByCategory_IdAndStatusAndDeletedAtIsNull(categoryId,
                    "ACTIVE", pageable);
        }

        return products.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> getMyProducts(CurrentUser currentUser, int page, int size) {

        requirePrivilege(currentUser, "PRODUCT.LIST");

        UUID sellerId = currentUser.id();

        validatePagination(page, size);

        log.debug("Getting seller products: sellerId={}, page={}, size={}", sellerId, page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<ProductResponse> responses = productRepository
                .findBySellerIdAndDeletedAtIsNull(sellerId, pageable).map(this::toResponse);

        log.debug("Finished getting seller products: sellerId={}, totalElements={}",
                sellerId, responses.getTotalElements());

        return responses;
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1) {
            throw new DomainException(ErrorCode.DATA_INVALID);
        }
    }

    //method này để check xem đúng owner product khum
    private Product findEditableProduct(UUID product_id, CurrentUser currentUser) {
        requirePrivilege(currentUser, "PRODUCT.UPDATE");
        Product product = productRepository.findByIdAndDeletedAtIsNull(product_id)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!currentUser.hasRole("ADMIN") && !product.getSellerId().equals(currentUser.id())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }
        return product;
    }

    //method check authen author
    private void requirePrivilege(CurrentUser currentUser, String privilege) {
        if (currentUser == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }

        if (!currentUser.hasPrivilege(privilege)) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
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
    private ProductDetailResponse toDetailResponse(Product product) {
        var skus = product.getSkuses().stream()
                .sorted(Comparator.comparing(Skus::getId))
                .map(sku -> new ProductDetailResponse.SkuResponse(
                        sku.getId(),
                        product.getId(),
                        sku.getSkuCode(),
                        sku.getColor(),
                        sku.getSize(),
                        sku.getPrice(),
                        sku.getStatus()
                ))
                .toList();

        var images = product.getProductImages().stream()
                .sorted(Comparator.comparing(ProductImage::getSortOrder)
                        .thenComparing(ProductImage::getId))
                .map(image -> new ProductDetailResponse.ImageResponse(
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
