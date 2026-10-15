package com.group5.catalogservice.product.service;

import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
public class SkuService {

    @Transactional (readOnly = true)
    public SkuResponse create (SkuRequest skuRequest, UUID id){

        log.info("Create new sku: id={}, ");

        return toResponse();
    }
}
