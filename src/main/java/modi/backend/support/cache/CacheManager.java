package modi.backend.support.cache;


import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.infra.cache.RedisPublisher;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.stereotype.Component;

/**
 * - 캐시를 다루는 유일한 창구 - 2단 조회 절차 - 캐시 실패 정책 - 캐시 무효화 방송 - 위의 모든 로직을 이 클래스 내부에서 관리
 * <p>
 * - 캐시 내부 동작은 외부로 노출하지 않음 - 캐시 되채움이 발생했는지 외부에서는 알 수 없음 - 캐시 조회가 실패했는지도 외부로 노출하지 않음
 * <p>
 * - 캐시 접근은 반드시 이 창구를 거쳐야 함 - 스프링 캐시 매니저를 직접 주입받아 우회하면 - 위와 같은 캡슐화 및 실패 정책이 모두 깨짐
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheManager {

    private final CaffeineCacheManager localCacheManager; // L1
    private final RedisCacheManager redisCacheManager; // L2
    private final RedisPublisher redisPublisher; // 무효화 방송. refresh/evict에 저장

    /**
     * - 조회 → 없으면 {@code loader}로 데이터를 생성하여 캐시를 채움 - 캐시 조회/저장 과정에서 발생하는 실패는 {@code get/put} 내부에서 처리 - 캐시 장애가 발생해도 예외를
     * 외부로 전파하지 않음 - 자연스럽게 {@code loader}를 통한 원본 데이터 조회로 폴백
     */
    public <T> T getOrPut(MyCache cache, String key, Class<T> clazz, Supplier<T> block) {
        T cached = get(cache, key, clazz);
        if (cached != null) {
            return cached;
        }
        T loaded = block.get(); // loader(DB) 예외만 던짐
        if (loaded != null) {
            put(cache, key, loaded);
        }
        return loaded;
    }

    public <T> T get(MyCache cache, String key, Class<T> clazz) {
        if (cache.getType() == CacheType.TWO_TIER) {
            T v1 = swallow(() -> local(cache).get(key, clazz));
            if (v1 != null) {
                return v1; // 1. L1 hit
            }
            T v2 = swallow(() -> redis(cache).get(key, clazz));
            if (v2 != null) {
                swallowRun(() -> local(cache).put(key, v2));    // 2 L2 Hit --→ L1 backfill
                return v2;
            }
            return null; // 3. All Miss --→ getOrPut이 loader로
        }
        return swallow(() -> getByCache(cache).get(key, clazz));
    }

    public void put(MyCache cache, String key, Object value) {
        if (cache.getType() == CacheType.TWO_TIER) {
            swallowRun(() -> redis(cache).put(key, value));     // L2 먼저 — 전 서버 SOT 때문
            swallowRun(() -> local(cache).put(key, value));
            return;
        }
        swallowRun(() -> getByCache(cache).put(key, value));
    }

    /**
     * evict = L2 삭제 + 자기 L1 삭제 + 방송.
     */
    public void evict(MyCache cache, String key) {
        swallowRun(() -> redis(cache).evict(key));
        swallowRun(() -> local(cache).evict(key));
        swallowRun(() -> redisPublisher.publish(cache.getName() + ":" + key));
    }

    /**
     * refresh = L2에 새 값 + 방송(전 서버 L1 evict). 배치 재적재용.
     */
    public void refresh(MyCache cache, String key, Object value) {
        swallowRun(() -> redis(cache).put(key, value));
        swallowRun(() -> redisPublisher.publish(cache.getName() + ":" + key));
    }

    public void evictLocal(String cacheName, String key) {
        Cache cache = localCacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);       // 없어도, 중복이어도 무해 (멱등)
        }
    }

    public void clearLocal() {
        for (String name : localCacheManager.getCacheNames()) {
            Cache cache = localCacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * 선언의 타입이 저장소를 결정한다. {@link CacheType}이 enum이라 케이스를 빠뜨리면 컴파일 에러다
     * (완전성은 sealed가 아니라 enum에서 온다 — 그래서 default를 넣지 않는다).
     */
    private Cache getByCache(MyCache cache) {
        return switch (cache.getType()) {
            case LOCAL -> local(cache);
            case REDIS, TWO_TIER -> redis(cache);
        };
    }

    private Cache local(MyCache cache) {
        return localCacheManager.getCache(cache.getName());
    }

    private Cache redis(MyCache cache) {
        return redisCacheManager.getCache(cache.getName());
    }

    /**
     * - 캐시 조회/저장 과정에서 발생한 예외는 외부로 전파하지 않음 - 캐시 장애가 발생해도 본 요청이 실패하지 않도록 처리 - 예외는 로그로만 남김
     * <p>
     * - 캐시 실패 이후의 폴백은 호출부의 흐름이 담당 - 캐시 조회 실패 → {@code null} 반환 - 호출부에서 {@code loader}를 실행하여 원본 데이터를 조회
     */
    private <T> T swallow(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("캐시 조회 실패, DB로 폴백한다", e);
            return null;
        }
    }

    private void swallowRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("캐시 반영 실패, 건너뛴다", e);
        }
    }


}
