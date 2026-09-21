package cc.ataglace.molebutter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import cc.ataglace.molebutter.infra.redis.RedisKeyValueStore;
import cc.ataglace.molebutter.service.KeyValueStore;

@Configuration
public class RedisConfig {

    /**
     * 저장 값이 전부 문자열(refresh jti, blacklist 플래그, 인증코드, rate limit 카운터)이라
     * 직렬화를 StringRedisSerializer로 통일한다.
     * 기본 JDK 직렬화를 쓰면 카운터 값이 바이너리로 저장되어 INCR({@code increment})가 깨진다.
     */
    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }

    /** 운영 기본 {@link KeyValueStore} 구현. 재시작 후에도 refresh token/blacklist가 유지된다. */
    @Bean
    KeyValueStore keyValueStore(RedisTemplate<String, Object> redisTemplate) {
        return new RedisKeyValueStore(redisTemplate);
    }
}
