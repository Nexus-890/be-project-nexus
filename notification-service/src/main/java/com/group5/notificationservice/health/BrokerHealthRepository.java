package com.group5.notificationservice.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrokerHealthRepository extends JpaRepository<BrokerHealth, String> {

    List<BrokerHealth> findAllByOrderByNameAsc();
}
