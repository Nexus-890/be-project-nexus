package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayPermissionsTest {

    private static final Set<Row> EXPECTED_PUBLIC = Set.of(
            new Row("/api/v1/auth/register", "POST", "Public"),
            new Row("/api/v1/auth/login", "POST", "Public"),
            new Row("/.well-known/jwks.json", "GET", "Public"),
            new Row("/api/v1/products", "GET", "Public"),
            new Row("/api/v1/products/{id}", "GET", "Public"),
            new Row("/api/v1/categories/{id}", "GET", "Public"),
            new Row("/api/v1/categories/tree", "GET", "Public"),
            new Row("/api/v1/products/{id}/skus", "GET", "Public"),
            new Row("/api/v1/skus/{id}", "GET", "Public"));

    @Test
    void documentAndSecurityConfigContainTheSameGatewayPermissionRows() throws Exception {
        assertPermissions(readRows(Path.of("../docs/api/gateway-permissions.md")));
    }

    private static void assertPermissions(Set<Row> docRows) {
        Set<Row> codeRows = new HashSet<>();
        for (SecurityConfig.Endpoint endpoint : SecurityConfig.ENDPOINTS) {
            codeRows.add(new Row(
                    endpoint.path(),
                    endpoint.method().name(),
                    endpoint.isPublic() ? "Public" : endpoint.privilege()));
        }

        assertThat(docRows).isEqualTo(codeRows);
        assertThat(codeRows).hasSize(SecurityConfig.ENDPOINTS.size());
        assertThat(docRows.stream().filter(row -> row.access().equals("Public")).collect(Collectors.toSet()))
                .isEqualTo(EXPECTED_PUBLIC);
    }

    @Test
    void comparisonFailsForMissingOrExtraPublicDocumentRow() throws Exception {
        Set<Row> document = readRows(Path.of("../docs/api/gateway-permissions.md"));
        Set<Row> missing = new HashSet<>(document);
        missing.remove(new Row("/api/v1/auth/login", "POST", "Public"));
        assertThatThrownBy(() -> assertPermissions(missing))
                .isInstanceOf(AssertionError.class);

        Set<Row> extra = new HashSet<>(document);
        extra.add(new Row("/api/v1/products", "POST", "Public"));
        assertThatThrownBy(() -> assertPermissions(extra))
                .isInstanceOf(AssertionError.class);
    }

    private static Set<Row> readRows(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Set<Row> rows = new HashSet<>();
        for (String line : lines) {
            if (!line.startsWith("|")) {
                continue;
            }
            String[] columns = line.split("\\|");
            if (columns.length < 2 || !unquote(columns[1]).startsWith("/")) {
                continue;
            }
            assertThat(columns).hasSize(5);
            assertThat(columns[4].trim()).as("owning issue for %s", columns[1]).isNotBlank();
            Row row = new Row(unquote(columns[1]), columns[2].trim(), unquote(columns[3]));
            assertThat(rows.add(row)).as("duplicate permission row %s", row).isTrue();
        }
        return rows;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("`") && trimmed.endsWith("`")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    record Row(String path, String method, String access) {
    }
}
