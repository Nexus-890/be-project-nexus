package com.group5.notificationservice.notification;

import com.group5.common_events.*;
import com.group5.notificationservice.delivery.*;
import com.group5.notificationservice.health.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MockitoBean(types={BrokerHealthJob.class,HealthEventWriter.class,KafkaBrokerProbe.class})
@SpringBootTest(properties={"spring.config.location=optional:classpath:/offer-email-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/notification-service.yml",
        "spring.cloud.config.enabled=false","spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false","eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false","spring.kafka.admin.auto-create=false",
        "nexus.outbox.relay.enabled=false","nexus.notification.email-enabled=true",
        "nexus.notification.delivery.poll-interval=1h","management.health.redis.enabled=false"})
class SecondChanceEmailIntegrationTest {
    static final String ADMIN=System.getenv("TEST_POSTGRES_ADMIN_URL");
    static final String DATABASE="offer_email_"+UUID.randomUUID().toString().replace("-","");
    static PostgreSQLContainer postgres;
    static GenericContainer<?> mailpit;
    static String url,user,password,http,host;
    static int smtp;
    @DynamicPropertySource static void runtime(DynamicPropertyRegistry r) throws Exception {
        if(ADMIN==null) {
            postgres=new PostgreSQLContainer("postgres:18");postgres.start();
            url=postgres.getJdbcUrl();user=postgres.getUsername();password=postgres.getPassword();
        } else {
            user=System.getenv().getOrDefault("TEST_POSTGRES_USERNAME","postgres");password=System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","");
            try(var c=DriverManager.getConnection(ADMIN,user,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+DATABASE);}
            url=ADMIN.substring(0,ADMIN.lastIndexOf('/')+1)+DATABASE;
        }
        http=System.getenv("TEST_MAILPIT_HTTP_URL");
        if(http==null) {mailpit=new GenericContainer<>("axllent/mailpit:v1.31").withExposedPorts(1025,8025);mailpit.start();host=mailpit.getHost();smtp=mailpit.getMappedPort(1025);http="http://"+host+":"+mailpit.getMappedPort(8025);}
        else {host="127.0.0.1";smtp=Integer.parseInt(System.getenv("TEST_MAILPIT_SMTP_PORT"));}
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->user);r.add("spring.datasource.password",()->password);
        r.add("spring.mail.host",()->host);r.add("spring.mail.port",()->smtp);
    }
    @AfterAll static void close() throws Exception {
        if(postgres!=null)postgres.stop();else try(var c=DriverManager.getConnection(ADMIN,user,password);var s=c.createStatement()){s.execute("DROP DATABASE IF EXISTS "+DATABASE+" WITH (FORCE)");}
        if(mailpit!=null)mailpit.stop();
    }
    @Autowired SecondChanceNotificationConsumer consumer;
    @Autowired DomainEventConsumer domain;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryRetryJob job;
    @MockitoBean RecipientDirectory recipients;
    @MockitoBean JwtDecoder decoder;
    UUID auction,offer,bidder,seller;
    @BeforeEach void prepare() throws Exception {
        auction=UUID.randomUUID();offer=UUID.randomUUID();bidder=UUID.randomUUID();seller=UUID.randomUUID();
        jdbc.execute("TRUNCATE notifications CASCADE");jdbc.execute("TRUNCATE notification_offer_outcomes,processed_events");
        request("DELETE","/api/v1/messages");reset(recipients);
        when(recipients.emailOf(bidder)).thenReturn("bidder208@example.test");when(recipients.emailOf(seller)).thenReturn("seller208@example.test");
    }
    @Test void offerEmailsOnlyAfterStockReadyAndDeduplicatesBusinessOutcome() throws Exception {
        var offered=record("SecondChanceOffered");domain.receive(offered);consumer.receive(offered);
        assertThat(count()).isZero();assertThat(messages()).isZero();
        var ready=record("SecondChanceOfferReady");consumer.receive(ready);consumer.receive(ready);consumer.receive(record("SecondChanceOfferReady"));
        assertThat(count()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT user_id FROM notifications",UUID.class)).isEqualTo(bidder);
        job.runOnce();assertThat(messages()).isEqualTo(1);
        String text=request("GET","/api/v1/messages");assertThat(text).contains("bidder208@example.test").doesNotContain("seller208@example.test");
        assertThat(jdbc.queryForObject("SELECT content FROM notifications",String.class)).contains("4800000.00","VND","/my-bids");
    }
    @Test void acceptDeclineAndExpiryEmailSellerOnceAndNeverTheBidder() throws Exception {
        for(String type:List.of("SecondChanceAccepted","SecondChanceDeclined","SecondChanceExpired")) {
            offer=UUID.randomUUID();var event=record(type);consumer.receive(event);consumer.receive(event);consumer.receive(record(type));
        }
        assertThat(count()).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id<>?",Integer.class,seller)).isZero();
        job.runOnce();assertThat(messages()).isEqualTo(3);
        assertThat(request("GET","/api/v1/messages")).contains("seller208@example.test").doesNotContain("bidder208@example.test");
    }
    @Test void failedNotificationInsertRollsBackMarkerAndOutcomeAndCanRetry() {
        var event=record("SecondChanceOfferReady");
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT reject_offer CHECK(type<>'SECOND_CHANCE_OFFER')");
        try{assertThatThrownBy(()->consumer.receive(event)).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT reject_offer");}
        assertThat(count()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_offer_outcomes",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Integer.class,event.value().eventId())).isZero();
        consumer.receive(event);assertThat(count()).isEqualTo(1);
    }
    private int count(){return jdbc.queryForObject("SELECT count(*) FROM notifications",Integer.class);}
    private ConsumerRecord<String,EventEnvelope<JsonNode>> record(String type) {
        var payload=JsonMapper.shared().valueToTree(Map.of("offerId",offer,"auctionId",auction,"sellerId",seller,"bidderId",bidder,"amount","4800000.00","currency","VND","expiresAt",Instant.now().plusSeconds(86400).toString()));
        return new ConsumerRecord<>(Topics.AUCTION,0,0,auction.toString(),new EventEnvelope<>(UUID.randomUUID(),type,auction.toString(),Instant.now(),"208-email",payload));
    }
    private int messages() throws Exception {return JsonMapper.shared().readTree(request("GET","/api/v1/messages")).path("total").asInt();}
    private String request(String method,String path) throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(http+path)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200,299);return response.body();
    }
}
