package com.group5.auctionservice.offer;

import com.group5.auctionservice.TestPostgres;
import com.group5.auctionservice.reputation.*;
import com.group5.auctionservice.settlement.SettlementStatusUpdater;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.*;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(args={"--spring.config.location=optional:classpath:/settlement-test.properties",
        "--spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "--spring.cloud.config.enabled=false","--spring.cloud.config.import-check.enabled=false"},properties={
        "spring.cloud.discovery.enabled=false","eureka.client.enabled=false","spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false","nexus.auction.scheduler.enabled=false","nexus.outbox.relay.enabled=false"})
class SecondChanceOfferIntegrationTest {
    static final TestPostgres PG=new TestPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        PG.start();r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
    }
    @AfterAll static void stop(){PG.stop();}
    @Autowired JdbcTemplate jdbc;
    @Autowired SecondChanceOfferService offers;
    @Autowired AuctionPaymentTimeoutHandler timeouts;
    @Autowired SettlementStatusUpdater settlements;
    @Autowired Tracer tracer;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean ReputationGuard guard;
    UUID seller,first,auction,order;
    CurrentUser owner;
    @BeforeEach void seed() {
        seller=UUID.randomUUID();first=UUID.randomUUID();order=UUID.randomUUID();
        owner=user(seller,"SELLER");
        auction=jdbc.queryForObject("""
                INSERT INTO auctions(product_id,sku_id,seller_id,title,image_url,starting_price,bid_increment,current_price,
                  status,start_at,end_at,original_end_at,winner_id,final_price,payment_due_at,order_id,settled_at)
                VALUES(gen_random_uuid(),gen_random_uuid(),?,'Offer proof','https://example.com/offer.png',100,10,500,
                  'SETTLED',now()-interval '3 days',now()-interval '2 days',now()-interval '2 days',?,500,
                  now()-interval '1 day',?,now()-interval '2 days') RETURNING id
                """,UUID.class,seller,first,order);
    }
    @Test void timeoutIsOncePerOrderIncludingDifferentEnvelopeAndIgnoresOtherOrders() {
        var cancel=cancel(order,"AUCTION","PAYMENT_DEADLINE_EXPIRED");
        timeouts.receive(cancel);timeouts.receive(cancel);timeouts.receive(cancel(order,"AUCTION","PAYMENT_DEADLINE_EXPIRED"));
        assertThat(state()).isEqualTo("PAYMENT_TIMEOUT");assertThat(events("AuctionPaymentTimeout")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT winner_id FROM auction_payment_timeouts WHERE order_id=?",UUID.class,order)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_offers WHERE auction_id=?",Integer.class,auction)).isZero();
    }
    @Test void standardWrongReasonAndStaleOrderDoNotTimeout() {
        timeouts.receive(cancel(order,"STANDARD","PAYMENT_DEADLINE_EXPIRED"));
        timeouts.receive(cancel(order,"AUCTION","BUYER_CANCELLED"));
        timeouts.receive(cancel(UUID.randomUUID(),"AUCTION","PAYMENT_DEADLINE_EXPIRED"));
        assertThat(state()).isEqualTo("SETTLED");assertThat(events("AuctionPaymentTimeout")).isZero();
    }
    @Test void timeoutAndProcessedMarkerRollbackTogether() {
        var event=cancel(order,"AUCTION","PAYMENT_DEADLINE_EXPIRED");
        new TransactionTemplate(transactions).executeWithoutResult(tx->{timeouts.receive(event);tx.setRollbackOnly();});
        assertThat(state()).isEqualTo("SETTLED");assertThat(events("AuctionPaymentTimeout")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Integer.class,event.value().eventId())).isZero();
        timeouts.receive(event);assertThat(events("AuctionPaymentTimeout")).isEqualTo(1);
    }
    @Test void cancellationBeforeOrderProjectionIsDurable() {
        jdbc.update("UPDATE auctions SET status='WON',order_id=NULL,settled_at=NULL WHERE id=?",auction);
        timeouts.receive(cancel(order,"AUCTION","PAYMENT_DEADLINE_EXPIRED"));
        assertThat(state()).isEqualTo("WON");assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_pending_timeouts WHERE order_id=?",Integer.class,order)).isEqualTo(1);
        settlements.receive(created(order,null));
        assertThat(state()).isEqualTo("PAYMENT_TIMEOUT");assertThat(events("AuctionPaymentTimeout")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_pending_timeouts WHERE order_id=?",Integer.class,order)).isZero();
    }
    @Test void previewHasNoSideEffectsAndSelectionUsesEachBiddersHighestValidBid() {
        timeout();UUID buyer=UUID.randomUUID(),skip=UUID.randomUUID();
        bid(first,9999,"PLACED");bid(buyer,480,"PLACED");bid(buyer,450,"OUTBID");bid(UUID.randomUUID(),600,"CANCELLED");bid(skip,490,"PLACED");
        doThrow(new DomainException(AuctionErrorCode.AUCTION_REPUTATION_TOO_LOW)).when(guard).requireEligible(skip,EligibilityAction.BID);
        var preview=offers.seller(auction,owner);assertThat(preview.candidate().bidderId()).isEqualTo(buyer);
        assertThat(preview.candidate().amount()).isEqualByComparingTo("480");assertThat(events("SecondChanceOffered")).isZero();
        var sent=trace(()->offers.send(auction,owner));assertThat(sent.offers()).hasSize(1);assertThat(sent.canOffer()).isFalse();
        assertThat(sent.offers().getFirst().amount()).isEqualByComparingTo("480");
        assertThatThrownBy(()->trace(()->offers.send(auction,owner))).isInstanceOf(DomainException.class).hasMessageContaining("already open");
    }
    @Test void declineThenExpirySkipOfferedBiddersWithoutPenaltyAndNoCandidatesIs409() {
        timeout();UUID b=UUID.randomUUID(),c=UUID.randomUUID();bid(b,480,"PLACED");bid(c,470,"PLACED");
        var one=send();trace(()->offers.respond(one.id(),user(b,"BUYER"),false));
        assertThatThrownBy(()->trace(()->offers.respond(one.id(),user(b,"BUYER"),true))).isInstanceOf(DomainException.class);
        var two=send();assertThat(two.bidderId()).isEqualTo(c);
        jdbc.update("UPDATE auction_offers SET offered_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE id=?",two.id());
        trace(()->{offers.expire(two.id());offers.expire(two.id());return null;});
        assertThat(events("SecondChanceDeclined")).isEqualTo(1);assertThat(events("SecondChanceExpired")).isEqualTo(1);
        assertThat(events("AuctionPaymentTimeout")).isEqualTo(1);
        assertThat(offers.seller(auction,owner).canOffer()).isFalse();
        assertThatThrownBy(()->trace(()->offers.send(auction,owner))).isInstanceOf(DomainException.class).hasMessageContaining("No eligible");
    }
    @Test void acceptRequiresStockAndCreatesFreshRoundWhileOldOrderEventsAreHarmless() {
        timeout();UUID b=UUID.randomUUID();bid(b,480,"PLACED");var offer=send();
        assertThatThrownBy(()->trace(()->offers.respond(offer.id(),user(b,"BUYER"),true))).isInstanceOf(DomainException.class).hasMessageContaining("Stock confirmation");
        trace(()->{offers.stock(offer.id(),auction,true);offers.stock(offer.id(),auction,true);return null;});
        assertThat(events("SecondChanceOfferReady")).isEqualTo(1);
        Instant before=Instant.now();var accepted=trace(()->offers.respond(offer.id(),user(b,"BUYER"),true));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");assertThat(accepted.orderId()).isNull();
        assertThat(accepted.paymentDueAt()).isBetween(before.plusSeconds(86400),Instant.now().plusSeconds(86400));
        assertThat(state()).isEqualTo("WON");settlements.receive(created(order,null));assertThat(state()).isEqualTo("WON");
        UUID newOrder=UUID.randomUUID();settlements.receive(created(newOrder,offer.id()));assertThat(state()).isEqualTo("SETTLED");
        assertThat(offers.mine(b,0,10).getContent().getFirst().orderId()).isEqualTo(newOrder);
        timeouts.receive(cancel(order,"AUCTION","PAYMENT_DEADLINE_EXPIRED"));assertThat(state()).isEqualTo("SETTLED");
        assertThatThrownBy(()->trace(()->offers.respond(offer.id(),user(b,"BUYER"),true))).isInstanceOf(DomainException.class);
    }
    @Test void ownershipAndLateResponsesAreEnforcedThroughHttp() throws Exception {
        timeout();UUID b=UUID.randomUUID();bid(b,480,"PLACED");var offer=send();
        var mvc=webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
        jwt("other",UUID.randomUUID(),"BUYER",List.of("AUCTION.BID"));
        mvc.perform(post("/api/v1/auctions/offers/"+offer.id()+"/accept").header("Authorization","Bearer other")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFER_NOT_YOURS"));
        jwt("seller",UUID.randomUUID(),"SELLER",List.of("AUCTION.LIST"));
        mvc.perform(get("/api/v1/auctions/"+auction+"/second-chance-offers").header("Authorization","Bearer seller")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auctions/offers/me")).andExpect(status().isUnauthorized());
        jwt("buyer",b,"BUYER",List.of("AUCTION.BID","AUCTION.VIEW_BID_HISTORY"));
        jdbc.update("UPDATE auction_offers SET offered_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE id=?",offer.id());
        mvc.perform(post("/api/v1/auctions/offers/"+offer.id()+"/decline").header("Authorization","Bearer buyer")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        mvc.perform(get("/api/v1/auctions/offers/me").header("Authorization","Bearer buyer")).andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1));
    }
    @Test void simultaneousSellerRequestsCreateExactlyOneOffer() throws Exception {
        timeout();bid(UUID.randomUUID(),480,"PLACED");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)tasks.add(executor.submit(()->{gate.await();try{send();return true;}catch(DomainException conflict){return false;}}));
            gate.countDown();int successes=0;for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);assertThat(events("SecondChanceOffered")).isEqualTo(1);
        }
    }
    @Test void acceptAndDeclineRaceHasOneOutcome() throws Exception {
        timeout();UUID b=UUID.randomUUID();bid(b,480,"PLACED");var offer=send();trace(()->{offers.stock(offer.id(),auction,true);return null;});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(boolean accept:List.of(true,false))tasks.add(executor.submit(()->{gate.await();try{trace(()->offers.respond(offer.id(),user(b,"BUYER"),accept));return true;}catch(DomainException conflict){return false;}}));
            gate.countDown();int successes=0;for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);assertThat(events("SecondChanceAccepted")+events("SecondChanceDeclined")).isEqualTo(1);
        }
    }
    @Test void unavailableEligibilityDoesNotBecomeNoCandidate() {
        timeout();UUID b=UUID.randomUUID();bid(b,480,"PLACED");
        doThrow(new DomainException(AuctionErrorCode.AUCTION_REPUTATION_UNAVAILABLE)).when(guard).requireEligible(b,EligibilityAction.BID);
        assertThatThrownBy(()->offers.seller(auction,owner)).isInstanceOf(DomainException.class).hasMessageContaining("temporarily unavailable");
    }
    private void timeout(){timeouts.receive(cancel(order,"AUCTION","PAYMENT_DEADLINE_EXPIRED"));}
    private SecondChanceOfferService.Offer send(){return trace(()->offers.send(auction,owner)).offers().getFirst();}
    private void bid(UUID buyer,int amount,String status){jdbc.update("INSERT INTO bids(auction_id,bidder_id,bidder_name,amount,status,idempotency_key,placed_at) VALUES (?,?,'B***r',?,?::bid_status,?,now())",auction,buyer,amount,status,UUID.randomUUID().toString());}
    private String state(){return jdbc.queryForObject("SELECT status::text FROM auctions WHERE id=?",String.class,auction);}
    private int events(String type){return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type=?",Integer.class,auction.toString(),type);}
    private <T>T trace(Supplier<T> work){var span=tracer.nextSpan().name("test.offer").start();try(var ignored=tracer.withSpan(span)){return work.get();}finally{span.end();}}
    private CurrentUser user(UUID id,String role){return new CurrentUser(id,Set.of(role),Set.of());}
    private void jwt(String token,UUID id,String role,List<String> privileges){when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg","RS256").subject(id.toString()).claim("roles",List.of(role)).claim("privileges",privileges).build());}
    private ConsumerRecord<String,EventEnvelope<JsonNode>> cancel(UUID orderId,String type,String reason){return record(Topics.COMMERCE,"OrderCancelled",orderId,Map.of("orderId",orderId,"referenceId",auction,"buyerId",first,"sellerId",seller,"orderType",type,"reason",reason,"cancelledAt",Instant.now().toString()));}
    private ConsumerRecord<String,EventEnvelope<JsonNode>> created(UUID id,UUID offer){var p=new HashMap<String,Object>();p.put("orderId",id);p.put("referenceId",auction);p.put("orderType","AUCTION");p.put("offerId",offer);return record(Topics.COMMERCE,"OrderCreated",id,p);}
    private ConsumerRecord<String,EventEnvelope<JsonNode>> record(String topic,String type,UUID id,Map<String,Object> p){return new ConsumerRecord<>(topic,0,0,id.toString(),new EventEnvelope<>(UUID.randomUUID(),type,id.toString(),Instant.now(),"208-proof",JsonMapper.shared().valueToTree(p)));}
}
