package cc.ataglace.molebutter.infra.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;

import cc.ataglace.molebutter.service.KeyValueStore;
import lombok.RequiredArgsConstructor;

/**
 * Redis 기반 {@link ExpiringValueStore}. 운영 기본 구현이며 재시작 후에도 refresh
 * token/blacklist가 유지된다.
 */
@RequiredArgsConstructor
public class RedisKeyValueStore implements KeyValueStore {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void put(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public Optional<String> get(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(value).map(Object::toString);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public long increment(String key, Duration ttlIfNew) {
        Long count = redisTemplate.opsForValue().increment(key);
        long value = count == null ? 1L : count;
        if (value == 1L) {
            redisTemplate.expire(key, ttlIfNew);
        }
        return value;
    }
}
