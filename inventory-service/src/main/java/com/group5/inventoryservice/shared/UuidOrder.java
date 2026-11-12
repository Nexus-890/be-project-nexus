package com.group5.inventoryservice.shared;

import java.util.Comparator;
import java.util.UUID;

public final class UuidOrder {

    public static final Comparator<UUID> DATABASE = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private UuidOrder() {
    }
}
