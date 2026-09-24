package cc.ataglace.molebutter.service;

import java.time.Duration;
import java.util.Optional;

public interface KeyValueStore {

    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    /** 원자적으로 읽고 삭제한다. 일회용 토큰/인증 증명의 중복 소비를 막는다. */
    Optional<String> getAndDelete(String key);

    boolean putIfAbsent(String key, String value, Duration ttl);

    /** 값이 일치할 때만 삭제한다. */
    boolean compareAndDelete(String key, String expected);

    void delete(String key);

    boolean exists(String key);

    /**
     * key의 카운터를 1 증가시키고 증가 후 값을 반환한다. 새로 생성된 경우(=1) ttl을 적용한다.
     * 로그인 rate limit 윈도우 카운팅에 사용한다.
     */
    long increment(String key, Duration ttlIfNew);
}
