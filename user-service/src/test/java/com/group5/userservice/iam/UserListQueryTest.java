package com.group5.userservice.iam;

import org.springframework.data.domain.Page;
import com.group5.userservice.iam.dto.UserListRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.group5.userservice.shared.PostgresTestDatabase;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserListQueryTest.JpaTestConfig.class)
class UserListQueryTest {

    private static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

    @Autowired
    private UserService userService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void insertUsers() {
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("delete from user_roles").executeUpdate();
            entityManager.createNativeQuery("delete from users").executeUpdate();
            entityManager.createNativeQuery("delete from roles").executeUpdate();
            Role buyer = role(entityManager, "BUYER");
            Role support = role(entityManager, "SUPPORT_STAFF");
            user(entityManager, "Dung Le", "dung@nexus.test", "0900000004", UserStatus.ACTIVE, false, buyer);
            user(entityManager, "An Nguyen", "an@nexus.test", "0900000001", UserStatus.ACTIVE, false, buyer);
            user(entityManager, "Em Vo", "em@nexus.test", "0900000005", UserStatus.LOCKED, false, buyer);
            user(entityManager, "Chi Pham", "chi@nexus.test", "0900000003", UserStatus.ACTIVE, false, support);
            user(entityManager, "Binh Tran", "binh@nexus.test", "0900000002", UserStatus.ACTIVE, false, buyer);
            user(entityManager, "Aaron Deleted", "deleted@nexus.test", "0900000009", UserStatus.DELETED, true, buyer);
        });
        STATEMENTS.clear();
    }

    @Test
    void fiveUsersPagedByTwoGiveTwoRowsAndTheTotalSortedByFullName() {
        Page<UserResponse> page = userService.listUsers(request(null, null, null, 0, 2, null, null));

        assertThat(page.getContent()).extracting(UserResponse::fullName).containsExactly("An Nguyen", "Binh Tran");
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void aDeletedUserIsNeverListed() {
        assertThat(userService.listUsers(request(null, null, null, 0, 20, null, null)).getContent())
                .extracting(UserResponse::email)
                .doesNotContain("deleted@nexus.test");
    }

    @Test
    void searchMatchesNameEmailOrPhone() {
        assertThat(names(request("CHI", null, null, 0, 20, null, null))).containsExactly("Chi Pham");
        assertThat(names(request("binh@", null, null, 0, 20, null, null))).containsExactly("Binh Tran");
        assertThat(names(request("0000004", null, null, 0, 20, null, null))).containsExactly("Dung Le");
    }

    @Test
    void statusAndRoleFiltersNarrowTheList() {
        assertThat(names(request(null, UserStatus.LOCKED, null, 0, 20, null, null))).containsExactly("Em Vo");
        assertThat(names(request(null, null, "support_staff", 0, 20, null, null))).containsExactly("Chi Pham");
    }

    @Test
    void anotherSortColumnAndDirectionCanBeChosen() {
        assertThat(names(request(null, null, null, 0, 20, "email", "desc")))
                .containsExactly("Em Vo", "Dung Le", "Chi Pham", "Binh Tran", "An Nguyen");
    }

    @Test
    void rolesOfAWholePageAreLoadedWithoutOneQueryPerUser() {
        Page<UserResponse> page = userService.listUsers(request(null, null, null, 0, 20, null, null));

        assertThat(page.getContent()).allSatisfy(user -> assertThat(user.roles()).isNotEmpty());
        assertThat(STATEMENTS).hasSizeLessThanOrEqualTo(3);
    }

    private List<String> names(UserListRequest request) {
        return userService.listUsers(request).getContent().stream().map(UserResponse::fullName).toList();
    }

    private static UserListRequest request(String q, UserStatus status, String role, int page, int size,
                                           String sort, String direction) {
        return new UserListRequest(q, status, role, page, size, sort, direction);
    }

    private static Role role(EntityManager entityManager, String code) {
        Role role = new Role();
        role.setCode(code);
        role.setName(code);
        entityManager.persist(role);
        return role;
    }

    private static void user(EntityManager entityManager, String name, String email, String phone,
                             UserStatus status, boolean deleted, Role role) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash("not-a-real-hash");
        user.setStatus(status);
        user.setDeletedAt(deleted ? OffsetDateTime.now() : null);
        user.assignInitialRole(role);
        entityManager.persist(user);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(PostgresAuditingTestConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaTestConfig {

        @Bean
        DataSource dataSource() {
            return PostgresTestDatabase.migratedDataSource("user_list");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                        STATEMENTS.add(sql);
                        return sql;
                    }));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        UserService userService(UserRepository repository, PlatformTransactionManager manager) {
            return new UserService(repository, new BCryptPasswordEncoder(4), new IamProperties(), manager);
        }
    }
}
