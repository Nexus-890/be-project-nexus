package com.group5.inventoryservice.inventory;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryMovementLedgerTest {

    private static final Pattern WRITES_TO_LEDGER = Pattern.compile(
            "(update\\s+(inventory_movements|inventorymovement)\\b"
                    + "|delete\\s+(from\\s+)?(inventory_movements|inventorymovement)\\b"
                    + "|truncate\\s+(table\\s+)?inventory_movements\\b)");

    @Test
    void theEntityHasNoSetterAndHibernateTreatsItAsImmutable() {
        assertThat(InventoryMovement.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(Arrays.stream(InventoryMovement.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
        assertThat(Arrays.stream(InventoryMovement.class.getDeclaredConstructors()))
                .allSatisfy(constructor -> assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse());
    }

    @Test
    void theRepositoryExposesNoSaveUpdateOrDelete() {
        assertThat(Arrays.stream(InventoryMovementRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("save") || name.startsWith("delete")
                        || name.startsWith("update") || name.startsWith("remove"));
    }

    @Test
    void noMainCodeIssuesUpdateOrDeleteOnTheLedger() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            offenders = files.filter(Files::isRegularFile)
                    .filter(path -> WRITES_TO_LEDGER.matcher(read(path)).find())
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }
}
