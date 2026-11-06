package com.kuropay.wallet.infrastructure.idempotency;

import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache.CachedResponse;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyCacheTest {

    private static final String REDIS_KEY = "kuropay:idempotency:idem-001";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisIdempotencyCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RedisIdempotencyCache(redisTemplate, JsonMapper.builder().build());
    }

    @Test
    void stored_response_should_be_read_back_with_its_request_fingerprint() {
        SampleResponse response = new SampleResponse("transfer-1", new BigDecimal("2500"));

        cache.store("idem-001", "fingerprint-abc", response);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(REDIS_KEY), json.capture(), eq(Duration.ofHours(24)));
        when(valueOperations.get(REDIS_KEY)).thenReturn(json.getValue());

        assertThat(cache.find("idem-001", SampleResponse.class))
                .contains(new CachedResponse<>("fingerprint-abc", response));
    }

    @Test
    void redis_outage_on_read_should_be_treated_as_a_cache_miss() {
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis down"));

        assertThat(cache.find("idem-001", SampleResponse.class)).isEmpty();
    }

    @Test
    void redis_outage_on_write_should_not_fail_the_request() {
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.store("idem-001", "fingerprint-abc", new SampleResponse("t", BigDecimal.ONE)))
                .doesNotThrowAnyException();
    }

    @Test
    void corrupt_cache_entry_should_be_treated_as_a_cache_miss() {
        when(valueOperations.get(REDIS_KEY)).thenReturn("{not valid json");

        assertThat(cache.find("idem-001", SampleResponse.class)).isEmpty();
    }

    record SampleResponse(String transferId, BigDecimal amount) {
    }
}
