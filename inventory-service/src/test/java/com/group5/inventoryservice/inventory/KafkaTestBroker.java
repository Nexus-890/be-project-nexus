package com.group5.inventoryservice.inventory;

import org.testcontainers.kafka.KafkaContainer;

/** Docker by default; a dedicated native broker can be supplied for the same consumer test. */
final class KafkaTestBroker extends KafkaContainer {
    private final String bootstrap=System.getenv("TEST_KAFKA_BOOTSTRAP_SERVERS");
    KafkaTestBroker(String image){super(image);}
    @Override public void start(){if(bootstrap==null)super.start();}
    @Override public void stop(){if(bootstrap==null)super.stop();}
    @Override public String getBootstrapServers(){return bootstrap==null?super.getBootstrapServers():bootstrap;}
}
