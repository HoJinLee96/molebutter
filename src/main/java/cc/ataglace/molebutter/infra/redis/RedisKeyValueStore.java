package cc.ataglace.molebutter.infra.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import cc.ataglace.molebutter.service.KeyValueStore;
import lombok.RequiredArgsConstructor;

/**
 * Redis 기반 {@link KeyValueStore}. 운영 기본 구현이며 재시작 후에도 refresh
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
    public Optional<String> getAndDelete(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(key)).map(Object::toString);
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    @Override
    public boolean compareAndDelete(String key, String expected) {
        Long result = redisTemplate.execute(new DefaultRedisScript<>("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
                """, Long.class), List.of(key), expected);
        return Long.valueOf(1).equals(result);
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
        Long count = redisTemplate.execute(new DefaultRedisScript<>("""
                local count = redis.call('INCR', KEYS[1])
                if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
                return count
                """, Long.class), List.of(key), Long.toString(ttlIfNew.toMillis()));
        if (count == null) throw new IllegalStateException("Redis counter returned no result");
        return count;
    }
}
