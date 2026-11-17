package com.group5.userservice.address;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.address.dto.AddressRequest;
import com.group5.userservice.address.dto.AddressResponse;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import com.group5.userservice.shared.PostgresTestDatabase;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AddressServiceTest.JpaTestConfig.class)
class AddressServiceTest {

    @Autowired
    private AddressService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID owner;
    private UUID stranger;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from user_addresses");
        jdbc.update("delete from phone_verifications");
        jdbc.update("delete from users");
        owner = seedUser("owner@nexus.local", "0900000001");
        stranger = seedUser("stranger@nexus.local", "0900000002");
    }

    @Test
    void theFirstAddressBecomesTheDefault() {
        AddressResponse first = service.create(owner, request("Home", null));
        AddressResponse second = service.create(owner, request("Office", false));

        assertThat(first.isDefault()).isTrue();
        assertThat(second.isDefault()).isFalse();
        assertThat(service.list(owner)).extracting(AddressResponse::addressLine).containsExactly("Home", "Office");
    }

    @Test
    void creatingWithIsDefaultMovesTheDefault() {
        service.create(owner, request("Home", null));
        AddressResponse office = service.create(owner, request("Office", true));

        assertThat(service.list(owner)).extracting(AddressResponse::addressLine, AddressResponse::isDefault)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Office", true),
                        org.assertj.core.groups.Tuple.tuple("Home", false));
        assertThat(defaults(owner)).containsExactly(office.id());
    }

    @Test
    void makeDefaultAndUpdateWithIsDefaultKeepExactlyOneDefault() {
        AddressResponse home = service.create(owner, request("Home", null));
        AddressResponse office = service.create(owner, request("Office", null));
        AddressResponse shop = service.create(owner, request("Shop", null));

        assertThat(service.makeDefault(owner, office.id()).isDefault()).isTrue();
        assertThat(defaults(owner)).containsExactly(office.id());

        AddressResponse updated = service.update(owner, shop.id(), request("Shop 2", true));
        assertThat(updated.addressLine()).isEqualTo("Shop 2");
        assertThat(defaults(owner)).containsExactly(shop.id());

        service.update(owner, home.id(), request("Home 2", null));
        assertThat(defaults(owner)).containsExactly(shop.id());
    }

    @Test
    void deletingTheDefaultPromotesTheMostRecentlyUpdatedAddress() {
        AddressResponse home = service.create(owner, request("Home", null));
        AddressResponse office = service.create(owner, request("Office", null));
        AddressResponse shop = service.create(owner, request("Shop", null));
        service.update(owner, office.id(), request("Office 2", null));

        service.delete(owner, home.id());

        assertThat(defaults(owner)).containsExactly(office.id());
        service.delete(owner, shop.id());
        service.delete(owner, office.id());
        assertThat(service.list(owner)).isEmpty();
    }

    @Test
    void theEleventhAddressIsRefused() {
        for (int index = 0; index < AddressService.MAX_ADDRESSES; index++) {
            service.create(owner, request("Line " + index, null));
        }

        assertThat(errorOf(() -> service.create(owner, request("One too many", null))))
                .isEqualTo("ADDRESS_LIMIT_REACHED");
        assertThat(service.list(owner)).hasSize(AddressService.MAX_ADDRESSES);
        assertThat(service.create(stranger, request("Stranger home", null)).isDefault()).isTrue();
    }

    @Test
    void anotherUsersAddressAnswersNotFound() {
        AddressResponse home = service.create(owner, request("Home", null));

        assertThat(errorOf(() -> service.update(stranger, home.id(), request("Taken", null))))
                .isEqualTo("ADDRESS_NOT_FOUND");
        assertThat(errorOf(() -> service.makeDefault(stranger, home.id()))).isEqualTo("ADDRESS_NOT_FOUND");
        assertThat(errorOf(() -> service.delete(stranger, home.id()))).isEqualTo("ADDRESS_NOT_FOUND");
        assertThat(service.list(stranger)).isEmpty();
        assertThat(service.list(owner)).extracting(AddressResponse::addressLine).containsExactly("Home");
    }

    @Test
    void theDatabaseAllowsOnlyOneDefaultPerUser() {
        service.create(owner, request("Home", null));

        assertThatThrownBy(() -> jdbc.update("""
                insert into user_addresses (id, user_id, recipient_name, phone, address_line, province_code,
                    province_name, ward_code, ward_name, is_default, created_at, updated_at, version)
                values (?, ?, 'R', '0900000001', 'Second default', '79', 'P', '26734', 'W', true, now(), now(), 0)
                """, UUID.randomUUID(), owner)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<UUID> defaults(UUID userId) {
        return jdbc.queryForList("select id from user_addresses where user_id = ? and is_default", UUID.class, userId);
    }

    private static AddressRequest request(String line, Boolean isDefault) {
        return new AddressRequest("Nguyen Van A", "0901234567", line, "79", "Ho Chi Minh",
                "26734", "Phuong Ben Thanh", isDefault);
    }

    private static String errorOf(Runnable action) {
        DomainException error = catchThrowableOfType(DomainException.class, action::run);
        assertThat(error).isNotNull();
        return error.getErrorCode();
    }

    private UUID seedUser(String email, String phone) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPhone(phone);
        user.setFullName("Address User");
        user.setIdentityNumber("ID-" + phone);
        user.setDateOfBirth(LocalDate.of(2000, 1, 2));
        user.setGender(Gender.FEMALE);
        user.setAddress("Address City");
        return users.saveAndFlush(user).getId();
    }

    @Import(PostgresAuditingTestConfiguration.class)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, UserAddressRepository.class})
    static class JpaTestConfig {

        @Bean
        DataSource dataSource() {
            return PostgresTestDatabase.migratedDataSource("user_address");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName(), UserAddress.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        AddressService addressService(UserAddressRepository addresses, UserRepository users) {
            return new AddressService(addresses, users);
        }
    }
}
