package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.dto.response.CarrierPerformanceResponse;
import com.group5.shipmentservice.shipping.repository.CarrierPerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CarrierPerformanceService {
    static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final CarrierPerformanceRepository repository;
    private final CarrierRegistry carriers;

    public CarrierPerformanceService(CarrierPerformanceRepository repository, CarrierRegistry carriers) {
        this.repository = repository;
        this.carriers = carriers;
    }

    @Transactional(readOnly = true)
    public List<CarrierPerformanceResponse> performance(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new DomainException(ShipmentErrorCode.INVALID_PERIOD);
        }
        Map<String, String> names = new LinkedHashMap<>();
        carriers.getAdapters().forEach(adapter -> names.put(adapter.code(), adapter.name()));
        Map<String, CarrierPerformanceResponse> rows = new LinkedHashMap<>();
        for (CarrierPerformanceRepository.Totals row : repository.totals(from, to)) {
            rows.put(row.carrier(), new CarrierPerformanceResponse(row.carrier(),
                    names.getOrDefault(row.carrier(), row.carrier()), row.shipments(), row.delivered(), row.failed(),
                    row.shipments() - row.delivered() - row.failed() - row.cancelled(), row.cancelled(),
                    ratio(row.delivered(), row.delivered() + row.failed()),
                    row.averageHours() == null ? null : row.averageHours().setScale(1, RoundingMode.HALF_UP),
                    ratio(row.onTime(), row.estimated())));
        }
        names.forEach((code, name) -> rows.putIfAbsent(code,
                new CarrierPerformanceResponse(code, name, 0, 0, 0, 0, 0, null, null, null)));
        List<CarrierPerformanceResponse> result = new ArrayList<>(rows.values());
        result.sort(Comparator.comparingLong(CarrierPerformanceResponse::shipments).reversed()
                .thenComparing(CarrierPerformanceResponse::carrier));
        return result;
    }

    private static BigDecimal ratio(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }
}
