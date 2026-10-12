package com.group5.catalogservice.product;

import org.hibernate.validator.constraints.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductResponse> getProductsByCategory(
            @RequestParam UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return productService.getProductsByCategory(
                categoryId, page, size
        );
    }

    @GetMapping("/mine")
    public Page<ProductResponse> getMyProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        // TODO: Lấy sellerId từ cơ chế xác thực hiện tại của project.
        // Không nhận sellerId từ client.
        throw new UnsupportedOperationException("Seller authentication integration is not implemented");
    }
}
