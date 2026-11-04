package com.group5.common_security.autoconfigure;

import com.group5.common_security.CurrentUserAuditor;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Loaded only in persistence services, including non-web consumer processes. */
@AutoConfiguration(afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass({EntityManagerFactory.class, EnableJpaAuditing.class})
@ConditionalOnBean(EntityManagerFactory.class)
@EnableJpaAuditing(auditorAwareRef = "currentUserAuditor")
public class JpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "currentUserAuditor")
    public CurrentUserAuditor currentUserAuditor() {
        return new CurrentUserAuditor();
    }
}
