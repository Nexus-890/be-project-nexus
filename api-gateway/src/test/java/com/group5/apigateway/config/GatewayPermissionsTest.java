package com.group5.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayPermissionsTest {

    private static final Set<Row> EXPECTED_PUBLIC = Set.of(
            new Row("/api/v1/carts/guest", "POST", "Public"),
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
        List<SecurityConfig.Endpoint> endpoints = bind(gatewayYaml()).endpoints();
        for (SecurityConfig.Endpoint endpoint : endpoints) {
            codeRows.add(new Row(
                    endpoint.path(),
                    endpoint.method().name(),
                    endpoint.isPublic() ? "Public" : endpoint.privilege()));
        }

        assertThat(docRows).isEqualTo(codeRows);
        assertThat(codeRows).hasSize(endpoints.size());
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

    @Test
    void endpointPoliciesAreBoundFromConfigurationWithoutJavaDefaults() {
        Properties yaml = gatewayYaml();
        yaml.setProperty("nexus.security.public-endpoints[1].path", "/api/v1/auth/configured-login");
        yaml.setProperty("nexus.security.protected-endpoints[0].privilege", "PROFILE.CONFIGURED");
        List<SecurityConfig.Endpoint> endpoints = bind(yaml).endpoints();

        assertThat(endpoints).contains(
                new SecurityConfig.Endpoint(HttpMethod.POST,
                        "/api/v1/auth/configured-login", null),
                new SecurityConfig.Endpoint(HttpMethod.GET,
                        "/api/v1/users/me", "PROFILE.CONFIGURED"));
        assertThat(endpoints).doesNotContain(
                new SecurityConfig.Endpoint(HttpMethod.POST,
                        "/api/v1/auth/login", null));
        assertThat(new SecurityConfig.GatewaySecurityProperties().endpoints()).isEmpty();
    }

    @Test
    void invalidEndpointConfigurationFailsInsteadOfWideningAccess() {
        for (String property : List.of(
                "nexus.security.public-endpoints[0].method",
                "nexus.security.public-endpoints[0].path",
                "nexus.security.protected-endpoints[0].privilege")) {
            Properties yaml = gatewayYaml();
            yaml.setProperty(property, "");
            assertThatThrownBy(() -> bind(yaml).endpoints()).isInstanceOf(RuntimeException.class);
        }
        Properties badMethod = gatewayYaml();
        badMethod.setProperty("nexus.security.public-endpoints[0].method", "TYPO");
        assertThatThrownBy(() -> bind(badMethod).endpoints()).isInstanceOf(IllegalArgumentException.class);
        Properties publicWithPrivilege = gatewayYaml();
        publicWithPrivilege.setProperty("nexus.security.public-endpoints[0].privilege", "PROFILE.VIEW");
        assertThatThrownBy(() -> bind(publicWithPrivilege).endpoints()).isInstanceOf(IllegalArgumentException.class);
        Properties duplicate = gatewayYaml();
        duplicate.setProperty("nexus.security.public-endpoints[0].path", "/api/v1/auth/login");
        assertThatThrownBy(() -> bind(duplicate).endpoints()).isInstanceOf(IllegalArgumentException.class);
    }

    static Properties gatewayYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/api-gateway.yml"));
        return yaml.getObject();
    }

    private static SecurityConfig.GatewaySecurityProperties bind(Properties yaml) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("gateway-yaml", yaml));
        return Binder.get(environment).bind("nexus.security", SecurityConfig.GatewaySecurityProperties.class)
                .orElseThrow(IllegalStateException::new);
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
