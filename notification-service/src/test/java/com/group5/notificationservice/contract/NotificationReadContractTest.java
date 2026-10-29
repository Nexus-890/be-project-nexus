package com.group5.notificationservice.contract;

import com.group5.common_security.CurrentUser;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.notificationservice.notification.Notification;
import com.group5.notificationservice.notification.NotificationController;
import com.group5.notificationservice.notification.NotificationRepository;
import com.group5.notificationservice.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Verifies notification HTTP serialization through the real service and a repository test double. */
class NotificationReadContractTest {
    private static final UUID USER_ID = UUID.fromString("68000000-0000-4000-8000-000000000002");
    private static final UUID NOTIFICATION_ID = UUID.fromString("68000000-0000-4000-8000-000000000003");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private NotificationRepository repository;
    private CurrentUser actor;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        repository = mock(NotificationRepository.class);
        actor = new CurrentUser(USER_ID, Set.of("BUYER"), Set.of("NOTIFICATION.VIEW"));
        mvc = standaloneSetup(new NotificationController(new NotificationService(repository)))
                .setControllerAdvice(new GlobalExceptionHandler(null))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CurrentUser.class;
                    }

                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                            NativeWebRequest request, WebDataBinderFactory binderFactory) {
                        return actor;
                    }
                }).build();
    }

    @Test
    void listUsesEnvelopeWithRequestedPageAndOnlyCurrentUsersNotifications() throws Exception {
        var page = PageRequest.of(1, 2, NEWEST_FIRST);
        when(repository.findByUserId(USER_ID, page)).thenReturn(new PageImpl<>(List.of(notification()), page, 5));

        mvc.perform(get("/api/v1/notifications").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data[0].id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.data[0].type").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.data[0].title").value("Order placed"))
                .andExpect(jsonPath("$.data[0].content").value("Your order was placed"))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.content").doesNotExist());
        verify(repository).findByUserId(USER_ID, page);
    }

    @Test
    void emptyListKeepsDefaultPaginationInSiblingPage() throws Exception {
        var page = PageRequest.of(0, 10, NEWEST_FIRST);
        when(repository.findByUserId(USER_ID, page)).thenReturn(new PageImpl<>(List.of(), page, 0));

        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        verify(repository).findByUserId(USER_ID, page);
    }

    @Test
    void unreadCountUsesSingleResourceEnvelopeForCurrentUser() throws Exception {
        when(repository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(3L);

        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.page").doesNotExist());
        verify(repository).countByUserIdAndReadAtIsNull(USER_ID);
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "0,0", "0,101"})
    void invalidPaginationUsesNotificationProblemCode(int page, int size) throws Exception {
        mvc.perform(get("/api/v1/notifications").param("page", Integer.toString(page)).param("size", Integer.toString(size)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOTIFICATION_DATA_INVALID"))
                .andExpect(jsonPath("$.detail").value("Invalid data"))
                .andExpect(jsonPath("$.instance").value("/api/v1/notifications"));
        verifyNoInteractions(repository);
    }

    @Test
    void missingActorUsesUnauthenticatedProblemForBothReadOperations() throws Exception {
        actor = null;
        for (String path : List.of("/api/v1/notifications", "/api/v1/notifications/unread-count")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.detail").value("Unauthenticated!"));
        }
        verifyNoInteractions(repository);
    }

    private static Notification notification() {
        var notification = new Notification();
        notification.setId(NOTIFICATION_ID);
        notification.setUserId(USER_ID);
        notification.setType("ORDER_CREATED");
        notification.setTitle("Order placed");
        notification.setContent("Your order was placed");
        notification.setCreatedAt(OffsetDateTime.parse("2026-10-29T00:00:00Z"));
        return notification;
    }
}
