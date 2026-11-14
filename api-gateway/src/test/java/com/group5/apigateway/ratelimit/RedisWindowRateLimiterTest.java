package com.group5.apigateway.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group5.apigateway.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisWindowRateLimiterTest {

    private ReactiveStringRedisTemplate redis;
    private RateLimitProperties properties;
    private RedisWindowRateLimiter limiter;
    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private boolean originalAdditive;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        properties = new RateLimitProperties();
        limiter = new RedisWindowRateLimiter(redis, properties);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RedisWindowRateLimiter.class);
        logAppender = new ListAppender<>();
        originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logger.setAdditive(originalAdditive);
    }

    @Test
    void allowsRequestWhenRedisReturnsAllowedResult() {
        redisReturns(List.of(1L, 99L, 60_000L));

        Response response = limiter.isAllowed("products", "127.0.0.1").block();

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsAllEntriesOf(Map.of(
                "X-RateLimit-Limit", "100",
                "X-RateLimit-Remaining", "99"));
    }

    @Test
    void deniesRequestWithRetryAfterCeiledFromRedisTtl() {
        redisReturns(List.of(0L, 0L, 1_001L));

        Response response = limiter.isAllowed("products", "127.0.0.1").block();

        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getHeaders()).containsEntry("Retry-After", "2");
    }

    @Test
    void usesOneSecondRetryAfterWhenRedisTtlIsBelowOneSecond() {
        redisReturns(List.of(0L, 0L, 1L));

        Response response = limiter.isAllowed("products", "127.0.0.1").block();

        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getHeaders()).containsEntry("Retry-After", "1");
    }

    @Test
    void sharesRedisKeyAcrossRoutesForTheSameClientIp() {
        redisReturns(List.of(1L, 99L, 60_000L));

        limiter.isAllowed("products", "127.0.0.1").block();
        limiter.isAllowed("users", "127.0.0.1").block();

        verify(redis, times(2)).execute(
                any(RedisScript.class),
                eq(List.of("nexus:rate-limit:ip:127.0.0.1")),
                anyString(),
                anyString());
    }

    @Test
    void readsChangedPropertiesForEachRequest() {
        redisReturns(List.of(1L, 99L, 60_000L));

        limiter.isAllowed("products", "127.0.0.1").block();
        properties.setRequestsPerMinute(150);
        limiter.isAllowed("products", "127.0.0.1").block();

        verify(redis).execute(any(RedisScript.class), anyList(), eq("100"), eq("60000"));
        verify(redis).execute(any(RedisScript.class), anyList(), eq("150"), eq("60000"));
    }

    @Test
    void auctionReadsUseTheirOwnKeyAndRefreshableQuota() {
        redisReturns(List.of(1L, 599L, 60_000L));
        var response = limiter.isAllowed("auction-read", "127.0.0.1").block();
        assertThat(response.getHeaders()).containsEntry("X-RateLimit-Limit", "600");

        properties.setAuctionReadRequestsPerMinute(750);
        limiter.isAllowed("auction-read", "127.0.0.1").block();
        limiter.isAllowed("auction-service", "127.0.0.1").block();

        var readKey = List.of("nexus:rate-limit:auction-read:ip:127.0.0.1");
        verify(redis).execute(any(RedisScript.class), eq(readKey), eq("600"), eq("60000"));
        verify(redis).execute(any(RedisScript.class), eq(readKey), eq("750"), eq("60000"));
        verify(redis).execute(any(RedisScript.class), eq(List.of("nexus:rate-limit:ip:127.0.0.1")),
                eq("100"), eq("60000"));
    }

    @Test
    void allowsRequestAndLogsWarnWhenRedisRaisesDataAccessException() {
        redisFailsWith(new DataAccessResourceFailureException("down"));

        Response response = limiter.isAllowed("products", "127.0.0.1").block();

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).isEmpty();
        assertThat(warnLogs()).hasSize(1);
    }

    @Test
    void logsOneWarnForAContinuousRedisOutage() {
        redisFailsWith(new DataAccessResourceFailureException("down"));

        limiter.isAllowed("products", "127.0.0.1").block();
        limiter.isAllowed("products", "127.0.0.1").block();

        assertThat(warnLogs()).hasSize(1);
    }

    @Test
    void logsWarnAgainAfterRedisRecoversAndFailsLater() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down")))
                .thenReturn(Flux.just(List.of(1L, 99L, 60_000L)))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down again")));

        limiter.isAllowed("products", "127.0.0.1").block();
        limiter.isAllowed("products", "127.0.0.1").block();
        limiter.isAllowed("products", "127.0.0.1").block();

        assertThat(warnLogs()).hasSize(2);
    }

    @Test
    void allowsRequestWhenRedisDoesNotRespondBeforeTimeout() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.never());

        Response response = limiter.isAllowed("products", "127.0.0.1")
                .block(Duration.ofSeconds(1));

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).isEmpty();
    }

    @Test
    void propagatesNonRedisErrors() {
        redisFailsWith(new IllegalArgumentException("bad script"));

        assertThatThrownBy(() -> limiter.isAllowed("products", "127.0.0.1").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad script");
    }

    @Test
    void propagatesMalformedRedisResults() {
        redisReturns(List.of(1L, 99L));

        assertThatThrownBy(() -> limiter.isAllowed("products", "127.0.0.1").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected Redis rate-limit result");
    }

    private void redisReturns(List<Long> result) {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.just(result));
    }

    private void redisFailsWith(RuntimeException error) {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(Flux.error(error));
    }

    private List<ILoggingEvent> warnLogs() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
    }
}
