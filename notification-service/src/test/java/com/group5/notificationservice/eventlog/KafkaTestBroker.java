package com.group5.notificationservice.eventlog;

import org.testcontainers.kafka.KafkaContainer;

/** Dedicated native broker for event-log assertions; Docker is still the default. */
final class KafkaTestBroker extends KafkaContainer {
    private final String bootstrap=System.getenv("TEST_NOTIFICATION_KAFKA_BOOTSTRAP_SERVERS");
    KafkaTestBroker(String image){super(image);}
    @Override public void start(){if(bootstrap==null)super.start();}
    @Override public void stop(){if(bootstrap==null)super.stop();}
    @Override public String getBootstrapServers(){return bootstrap==null?super.getBootstrapServers():bootstrap;}
}
