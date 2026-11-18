package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.category.dto.CategoryTreeResponse;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC/security filters and method authorization; service and JWT decoding are mocked. */
class CategoryControllerSecurityTest {
    private static final String CATEGORIES = "/api/v1/categories";
    private static final UUID CATEGORY_ID = UUID.fromString("68000000-0000-4000-8000-000000000099");
    private static final String CATEGORY_PATH = CATEGORIES + "/" + CATEGORY_ID;
    private static final String CREATE_BODY = """
            {"name":"Shoes","slug":"shoes","parentId":null,"description":"Category security test"}
            """;
    private static final String UPDATE_BODY = """
            {"name":"Updated Shoes","slug":"updated-shoes"}
            """;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CategoryService service;

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new YamlPropertySourceLoader().load("catalog", new FileSystemResource(
                "../config-service/src/main/resources/config/catalog-service.yml"))
                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
        context.register(TestConfig.class);
        context.refresh();

        // Use the Spring-managed controller proxy: standalone new CategoryController() bypasses @PreAuthorize.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        service = context.getBean(CategoryService.class);
        JwtDecoder decoder = context.getBean(JwtDecoder.class);
        when(decoder.decode("buyer-token")).thenReturn(jwt("buyer-token", "BUYER", "ORDER.CREATE"));
        when(decoder.decode("admin-create-token"))
                .thenReturn(jwt("admin-create-token", "ADMIN", "CATEGORY.CREATE"));
        when(decoder.decode("admin-update-token"))
                .thenReturn(jwt("admin-update-token", "ADMIN", "CATEGORY.UPDATE"));
        when(decoder.decode("admin-delete-token"))
                .thenReturn(jwt("admin-delete-token", "ADMIN", "CATEGORY.DELETE"));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void buyerCannotCreateCategory() throws Exception {
        expectForbidden(mvc.perform(withToken(post(CATEGORIES)
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY), "buyer-token")), CATEGORIES);

        verifyNoInteractions(service);
    }

    @Test
    void buyerCannotUpdateCategory() throws Exception {
        expectForbidden(mvc.perform(withToken(patch(CATEGORY_PATH)
                .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY), "buyer-token")), CATEGORY_PATH);

        verifyNoInteractions(service);
    }

    @Test
    void buyerCannotDeleteCategory() throws Exception {
        expectForbidden(mvc.perform(withToken(delete(CATEGORY_PATH), "buyer-token")), CATEGORY_PATH);

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH", "DELETE"})
    void writesWithoutTokenReturn401(String method) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY);
            case "PATCH" -> patch(CATEGORY_PATH).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY);
            default -> delete(CATEGORY_PATH);
        };
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(service);
    }

    @Test
    void categoryCreatePrivilegeAllows201AndCallsService() throws Exception {
        when(service.create(any())).thenReturn(category("Shoes", "shoes"));

        mvc.perform(withToken(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
                        "admin-create-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(CATEGORY_ID.toString()));

        verify(service).create(any());
    }

    @Test
    void categoryUpdatePrivilegeAllows200AndCallsService() throws Exception {
        when(service.update(eq(CATEGORY_ID), any())).thenReturn(category("Updated Shoes", "updated-shoes"));

        mvc.perform(withToken(patch(CATEGORY_PATH).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY),
                        "admin-update-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("Updated Shoes"));

        verify(service).update(eq(CATEGORY_ID), any());
    }

    @Test
    void categoryDeletePrivilegeAllows204AndCallsService() throws Exception {
        mvc.perform(withToken(delete(CATEGORY_PATH), "admin-delete-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(CATEGORY_ID);
    }

    @Test
    void categoryReadsStayPublic() throws Exception {
        when(service.getById(CATEGORY_ID)).thenReturn(category("Shoes", "shoes"));
        when(service.getCategoryTree()).thenReturn(List.of(
                new CategoryTreeResponse(CATEGORY_ID, null, "Shoes", "shoes", (short) 1, List.of())));

        mvc.perform(get(CATEGORY_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(CATEGORY_ID.toString()));
        mvc.perform(get(CATEGORIES + "/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(CATEGORY_ID.toString()));
    }

    @Test
    void invalidAdminPayloadStillReturns400BeforeCallingService() throws Exception {
        mvc.perform(withToken(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"slug\":\"shoes\"}"), "admin-create-token"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(service);
    }

    private static void expectForbidden(ResultActions result, String path) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:forbidden"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value(path))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static Jwt jwt(String token, String role, String privilege) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token).header("alg", "RS256")
                .subject("68000000-0000-4000-8000-000000000003")
                .issuedAt(now).expiresAt(now.plusSeconds(3600))
                .claim("roles", List.of(role)).claim("privileges", List.of(privilege)).build();
    }

    private static CategoryResponse category(String name, String slug) {
        return new CategoryResponse(CATEGORY_ID, null, name, slug, (short) 1, "/" + slug + "/", null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityAutoConfiguration.class)
    static class TestConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        CategoryService categoryService() {
            return mock(CategoryService.class);
        }

        @Bean
        CategoryController categoryController(CategoryService service) {
            return new CategoryController(service);
        }

        @Bean
        GlobalExceptionHandler errors() {
            return new GlobalExceptionHandler(null);
        }
    }
}
