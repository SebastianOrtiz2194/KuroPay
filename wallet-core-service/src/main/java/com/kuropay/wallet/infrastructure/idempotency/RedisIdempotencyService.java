package com.kuropay.wallet.infrastructure.idempotency;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisIdempotencyService {

    private static final String KEY_PREFIX = "kuropay:idempotency:";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    public <T> IdempotentExecution<T> executeIdempotently(
            String idempotencyKey,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String redisKey = KEY_PREFIX + idempotencyKey;

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, LOCK_TTL);
        if (Boolean.FALSE.equals(acquired)) {
            String existingValue = redisTemplate.opsForValue().get(redisKey);
            if (existingValue == null || IN_PROGRESS.equals(existingValue)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            T cachedResponse = jsonMapper.readValue(existingValue, responseType);
            return new IdempotentExecution<>(cachedResponse, true);
        }

        try {
            T freshResponse = action.get();
            String serialized = jsonMapper.writeValueAsString(freshResponse);
            redisTemplate.opsForValue().set(redisKey, serialized, COMPLETED_TTL);
            return new IdempotentExecution<>(freshResponse, false);
        } catch (RuntimeException ex) {
            redisTemplate.delete(redisKey);
            throw ex;
        }
    }

    public record IdempotentExecution<T>(T response, boolean replayed) {
    }
}
