package com.kuropay.wallet.infrastructure.idempotency;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Response cache in front of the database-enforced idempotency. It only saves work: a retry
 * served from here never takes row locks on the wallets.
 *
 * <p>Correctness does NOT depend on this class (the transfers and deposits tables do), so it fails open:
 * any Redis or serialization error is logged and treated as a cache miss.
 */
@Component
public class RedisIdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);

    private static final String KEY_PREFIX = "kuropay:idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public RedisIdempotencyCache(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    public <T> Optional<CachedResponse<T>> find(
            IdempotentOperation operation, String idempotencyKey, Class<T> responseType
    ) {
        try {
            String json = redisTemplate.opsForValue().get(redisKey(operation, idempotencyKey));
            if (json == null) {
                return Optional.empty();
            }
            JavaType type = jsonMapper.getTypeFactory().constructParametricType(CachedResponse.class, responseType);
            return Optional.of(jsonMapper.readValue(json, type));
        } catch (DataAccessException | JacksonException ex) {
            log.warn("Idempotency cache read failed for {} key '{}', falling back to database",
                    operation, idempotencyKey, ex);
            return Optional.empty();
        }
    }

    public void store(
            IdempotentOperation operation, String idempotencyKey, String requestFingerprint, Object response
    ) {
        try {
            String json = jsonMapper.writeValueAsString(new CachedResponse<>(requestFingerprint, response));
            redisTemplate.opsForValue().set(redisKey(operation, idempotencyKey), json, TTL);
        } catch (DataAccessException | JacksonException ex) {
            log.warn("Idempotency cache write failed for {} key '{}', the database still guards retries",
                    operation, idempotencyKey, ex);
        }
    }

    /**
     * The operation goes BEFORE the client's key: it comes from a fixed set without ':', so a
     * client cannot craft a key that lands in another operation's namespace.
     */
    private static String redisKey(IdempotentOperation operation, String idempotencyKey) {
        return KEY_PREFIX + operation.keySegment() + ":" + idempotencyKey;
    }

    /**
     * @param requestFingerprint hash of the original request, so a reused key with a different
     *                           payload can be rejected without touching the database
     */
    public record CachedResponse<T>(String requestFingerprint, T response) {
    }
}
