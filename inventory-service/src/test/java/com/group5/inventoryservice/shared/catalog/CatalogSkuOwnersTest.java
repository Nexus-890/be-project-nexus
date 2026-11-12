package com.group5.inventoryservice.shared.catalog;

import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogSkuOwnersTest {

    private final UUID sku = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private MockRestServiceServer server;
    private CatalogSkuOwners owners;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        owners = new CatalogSkuOwners(builder);
    }

    @Test
    void theOwnerIsTheSellerOfTheProductTheSkuBelongsTo() {
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + sku))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":0,"message":"response success","data":{"id":"%s","productId":"%s",
                         "skuCode":"RED-M","color":"Red","size":"M","price":120000,"status":"ACTIVE"}}
                        """.formatted(sku, product), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog-service/api/v1/products/" + product))
                .andRespond(withSuccess("""
                        {"code":0,"message":"response success","data":{"id":"%s","sellerId":"%s",
                         "name":"Shirt","status":"PUBLIC","skus":[],"images":[]}}
                        """.formatted(product, seller), MediaType.APPLICATION_JSON));

        assertThat(owners.sellerOf(sku)).contains(seller);
        server.verify();
    }

    @Test
    void anSkuOrProductTheCatalogDoesNotShowHasNoOwner() {
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + sku))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":404,\"code\":\"PRODUCT_SKU_NOT_FOUND\"}"));
        assertThat(owners.sellerOf(sku)).isEmpty();

        server.reset();
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + sku))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"id\":\"%s\",\"productId\":\"%s\"}}"
                        .formatted(sku, product), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog-service/api/v1/products/" + product))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(owners.sellerOf(sku)).isEmpty();
    }

    @Test
    void aCatalogFailureIsReportedAsUnavailableNotAsNotOwned() {
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + sku)).andRespond(withServerError());

        assertThatThrownBy(() -> owners.sellerOf(sku)).isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("INVENTORY_CATALOG_UNAVAILABLE");
    }
}
