package com.group5.common_events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AuctionWonTest {
    @Test void sharedContractRoundTripsMoneyAsStringAndDatesAsUtc() {
        var payload=payload("1500000.00",1);
        var json=JsonMapper.shared().valueToTree(payload);
        assertThat(json.get("finalPrice").isString()).isTrue();
        assertThat(json.get("paymentDueAt").asString()).endsWith("Z");
        assertThat(JsonMapper.shared().treeToValue(json,AuctionWon.class)).isEqualTo(payload);
    }
    @Test void rejectsInvalidAmountsAndUnsupportedQuantityBeforeCreatingOrder() {
        for (String price : new String[]{"1.5","-1.00","0.00","100000000000000000.00"}) {
            assertThatThrownBy(() -> payload(price,1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> payload("1000000.00",2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void carriesSkuCodeAndStaysReadableWithoutIt() {
        var base=payload("1500000.00",1);
        var withCode=new AuctionWon(base.auctionId(),base.sellerId(),base.productId(),base.skuId(),base.winnerId(),
                base.finalPrice(),base.currency(),base.title(),base.imageUrl(),1,base.endAt(),base.paymentDueAt(),null,"NX-ITEM-1");
        var json=JsonMapper.shared().valueToTree(withCode);
        assertThat(json.get("skuCode").asString()).isEqualTo("NX-ITEM-1");
        assertThat(JsonMapper.shared().treeToValue(json,AuctionWon.class)).isEqualTo(withCode);
        assertThat(JsonMapper.shared().valueToTree(base).has("skuCode")).isFalse();
        assertThatThrownBy(() -> new AuctionWon(base.auctionId(),base.sellerId(),base.productId(),base.skuId(),base.winnerId(),
                base.finalPrice(),base.currency(),base.title(),base.imageUrl(),1,base.endAt(),base.paymentDueAt(),null," "))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private AuctionWon payload(String price,int quantity) { return new AuctionWon(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            price,"VND","Item","https://example.com/item.jpg",quantity,Instant.parse("2031-01-01T00:00:00Z"),Instant.parse("2031-01-02T00:00:00Z")); }
}
