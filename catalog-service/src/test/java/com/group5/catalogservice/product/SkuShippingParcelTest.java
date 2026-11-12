package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.ShippingParcel;
import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkuShippingParcelTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final SkuRepository skus = mock(SkuRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SkuService service = new SkuService(skus, products);
    private final UUID sellerId = UUID.randomUUID();
    private final CurrentUser seller = new CurrentUser(sellerId, Set.of("SELLER"), Set.of("PRODUCT.UPDATE"));
    private final ShippingParcel parcel = new ShippingParcel(1200, 30, 20, 15);

    @AfterAll
    static void closeValidation() { VALIDATORS.close(); }

    @Test
    void sellerCreatePersistsVerifiedUnitPackageAndReturnsIt() {
        Product product = product();
        when(skus.findProductForSkuCreate(product.getId())).thenReturn(Optional.of(product));
        when(skus.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SkuRequest request = request();
        request.setShippingParcel(parcel);

        var response = service.create(product.getId(), request, seller);

        assertThat(response.getShippingParcel()).isEqualTo(parcel);
        verify(skus).saveAndFlush(argThat(saved -> saved.getShippingWeightGrams() == 1200
                && saved.getShippingLengthCm() == 30 && saved.getShippingWidthCm() == 20
                && saved.getShippingHeightCm() == 15 && saved.getProduct() == product));
    }

    @Test
    void legacyCreateLeavesPackageUnknownRatherThanFabricatingMeasurements() {
        Product product = product();
        when(skus.findProductForSkuCreate(product.getId())).thenReturn(Optional.of(product));
        when(skus.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(product.getId(), request(), seller).getShippingParcel()).isNull();
        verify(skus).saveAndFlush(argThat(saved -> saved.getShippingWeightGrams() == null
                && saved.getShippingLengthCm() == null && saved.getShippingWidthCm() == null
                && saved.getShippingHeightCm() == null));
    }

    @Test
    void updateReplacesAllMeasurementsAndOlderRequestsPreserveThem() {
        Skus sku = existingSku();
        SkuRequest request = request();
        request.setShippingParcel(parcel);

        assertThat(service.update(sku.getId(), request, seller).getShippingParcel()).isEqualTo(parcel);
        assertThat(service.update(sku.getId(), request(), seller).getShippingParcel()).isEqualTo(parcel);
        assertThat(service.getById(sku.getId()).getShippingParcel()).isEqualTo(parcel);
    }

    @Test
    void anotherSellerCannotChangeMeasurements() {
        Skus sku = existingSku();
        SkuRequest request = request();
        request.setShippingParcel(parcel);
        CurrentUser stranger = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of("PRODUCT.UPDATE"));

        assertThatThrownBy(() -> service.update(sku.getId(), request, stranger))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(403));
        assertThat(sku.getShippingWeightGrams()).isNull();
        verify(skus, never()).saveAndFlush(any());
    }

    @Test
    void optionalPackageAndInclusiveLimitsAreValid() {
        SkuRequest request = request();
        assertThat(VALIDATORS.getValidator().validate(request)).isEmpty();
        for (ShippingParcel boundary : new ShippingParcel[]{new ShippingParcel(1, 1, 1, 1),
                new ShippingParcel(50000, 200, 200, 200)}) {
            request.setShippingParcel(boundary);
            assertThat(VALIDATORS.getValidator().validate(request)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidPackages")
    void validatesNestedPackageWithoutAcceptingPartialOrOutOfRangeValues(ShippingParcel invalid, String field) {
        SkuRequest request = request();
        request.setShippingParcel(invalid);
        assertThat(VALIDATORS.getValidator().validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("shippingParcel." + field);
    }

    static Stream<Arguments> invalidPackages() {
        return Stream.of(
                Arguments.of(new ShippingParcel(null, 30, 20, 15), "weightGrams"),
                Arguments.of(new ShippingParcel(1200, null, 20, 15), "lengthCm"),
                Arguments.of(new ShippingParcel(1200, 30, null, 15), "widthCm"),
                Arguments.of(new ShippingParcel(1200, 30, 20, null), "heightCm"),
                Arguments.of(new ShippingParcel(0, 30, 20, 15), "weightGrams"),
                Arguments.of(new ShippingParcel(50001, 30, 20, 15), "weightGrams"),
                Arguments.of(new ShippingParcel(1200, 0, 20, 15), "lengthCm"),
                Arguments.of(new ShippingParcel(1200, 201, 20, 15), "lengthCm"),
                Arguments.of(new ShippingParcel(1200, 30, 0, 15), "widthCm"),
                Arguments.of(new ShippingParcel(1200, 30, 201, 15), "widthCm"),
                Arguments.of(new ShippingParcel(1200, 30, 20, 0), "heightCm"),
                Arguments.of(new ShippingParcel(1200, 30, 20, 201), "heightCm"));
    }

    private Product product() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(sellerId);
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private Skus existingSku() {
        Skus sku = new Skus();
        sku.setId(UUID.randomUUID());
        sku.setProduct(product());
        when(skus.findById(sku.getId())).thenReturn(Optional.of(sku));
        when(products.findByIdAndDeletedAtIsNull(sku.getProduct().getId())).thenReturn(Optional.of(sku.getProduct()));
        when(skus.saveAndFlush(sku)).thenReturn(sku);
        return sku;
    }

    private SkuRequest request() {
        SkuRequest request = new SkuRequest();
        request.setColor("Black");
        request.setSize("M");
        request.setPrice(new BigDecimal("150000.00"));
        return request;
    }
}
