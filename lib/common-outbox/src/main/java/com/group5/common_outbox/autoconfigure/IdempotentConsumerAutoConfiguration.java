package com.group5.common_outbox.autoconfigure;

import com.group5.common_outbox.IdempotentConsumer;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@ConditionalOnClass(EntityManagerFactory.class)
public class IdempotentConsumerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotentConsumer idempotentConsumer(
            EntityManagerFactory entityManagerFactory,
            PlatformTransactionManager transactionManager) {

        return new IdempotentConsumer(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory),
                new TransactionTemplate(transactionManager));
    }
}
