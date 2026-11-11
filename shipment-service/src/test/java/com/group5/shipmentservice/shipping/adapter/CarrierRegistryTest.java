package com.group5.shipmentservice.shipping.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CarrierRegistryTest {
    @Test
    void duplicateCodesFailBeforeServingRequests() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new CarrierRegistry(List.of(new FakeCarrierAdapter(), new FakeCarrierAdapter())))
                .withMessageContaining("FAKE");
    }

    @Test
    void missingCarrierIdentityFailsBeforeServingRequests() {
        CarrierAdapter invalid = org.mockito.Mockito.mock(CarrierAdapter.class);
        assertThatIllegalStateException().isThrownBy(() -> new CarrierRegistry(List.of(invalid)));
    }
}
