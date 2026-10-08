package modi.backend.support.cache;


import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.stereotype.Component;

/**
 * - 캐시를 다루는 유일한 창구
 *   - 계층 조회 순서, 캐시 실패 정책, 삭제를 이 클래스 안에서만 다룸
 *   - 캐시 접근은 반드시 이 창구를 거쳐야 함
 *   - 스프링 캐시 매니저를 직접 주입받아 우회하면 아래 실패 정책이 깨짐
 *
 * - 선언의 타입이 계층을 정함
 *   - TWO_TIER: L1(로컬) → L2(Redis) → DB. 전시 목록
 *   - REDIS: Redis → DB. 전시 상세(로컬 복사본 없음)
 *   - LOCAL: L1 → DB
 *
 * - 쓰기 뒤에는 삭제만 함(Cache-Aside)
 *   - 다른 서버에 삭제를 알리는 방송은 없음
 *   - 다른 서버의 L1은 L1 TTL로, 삭제가 누락된 Redis 키는 Redis TTL로 만료됨
 *
 * - Redis가 죽어도 응답은 나가야 함
 *   - 조회·적재·삭제의 실패는 전부 여기서 삼킴
 *   - 조회 실패는 미스로 취급되어 호출부가 DB로 감
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheManager {

    private final CaffeineCacheManager localCacheManager; // L1
    private final RedisCacheManager redisCacheManager; // L2
    private final CacheInvalidationMetrics invalidationMetrics; // 삭제 실패를 조용히 묻지 않기 위한 계측
    private final CacheLookupMetrics lookupMetrics; // 계층별 히트/미스 — 관리자 대시보드의 히트율 출처

    /**
     * - 캐시 전체 스위치(부하 실험용). 기본 true = 운영 동작 그대로
     *   - false면 조회는 항상 미스(L1·L2를 보지 않음)이고 적재도 하지 않음
     *   - 그래서 모든 조회가 loader(DB)로 내려감 — 캐시가 없을 때와 같은 비교군
     *   - 조회 계측도 남기지 않아, 꺼진 런에서는 lookup 카운터가 0인 것으로 우회를 확인할 수 있음
     * - 생성자 인자가 아니라 필드 주입이라, 테스트가 직접 생성하면 기본값(true)으로 동작함
     */
    @Value("${app.cache.enabled:true}")
    private boolean enabled = true;

    /** 캐시 스위치가 켜져 있는가. 꺼져 있으면 모든 조회가 DB로 간다. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * - 조회 → 없으면 {@code block}으로 원본을 읽어 캐시를 채움
     *   - 캐시 장애는 {@code get/put} 안에서 삼켜져 자연스럽게 원본 조회로 폴백
     *   - 원본(DB) 예외만 밖으로 나감
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
        if (!enabled) {
            return null; // 스위치 off — 항상 미스
        }
        return switch (cache.getType()) {
            case TWO_TIER -> getTwoTier(cache, key, clazz);
            case REDIS -> count(cache, swallow(() -> redis(cache).get(key, clazz)), false);
            case LOCAL -> count(cache, swallow(() -> local(cache).get(key, clazz)), true);
        };
    }

    private <T> T getTwoTier(MyCache cache, String key, Class<T> clazz) {
        T v1 = swallow(() -> local(cache).get(key, clazz));
        if (v1 != null) {
            lookupMetrics.l1Hit(cache);
            return v1; // 1. L1 hit
        }
        T v2 = swallow(() -> redis(cache).get(key, clazz));
        if (v2 != null) {
            swallowRun(() -> local(cache).put(key, v2)); // 2. L2 hit → L1 되채움
            lookupMetrics.l2Hit(cache);
            return v2;
        }
        lookupMetrics.miss(cache);
        return null; // 3. 전부 미스 → getOrPut이 loader로
    }

    /** 단일 계층 캐시의 히트/미스를 센다. LOCAL은 L1 히트로, REDIS는 L2 히트로 센다. */
    private <T> T count(MyCache cache, T value, boolean local) {
        if (value == null) {
            lookupMetrics.miss(cache);
        } else if (local) {
            lookupMetrics.l1Hit(cache);
        } else {
            lookupMetrics.l2Hit(cache);
        }
        return value;
    }

    public void put(MyCache cache, String key, Object value) {
        if (!enabled) {
            return; // 스위치 off — 적재하지 않음
        }
        switch (cache.getType()) {
            case TWO_TIER -> {
                swallowRun(() -> redis(cache).put(key, value)); // L2 먼저 — 두 서버가 함께 보는 값
                swallowRun(() -> local(cache).put(key, value));
            }
            case REDIS -> swallowRun(() -> redis(cache).put(key, value));
            case LOCAL -> swallowRun(() -> local(cache).put(key, value));
        }
    }

    /**
     * - 이 키를 Redis와 이 서버의 L1에서 지움
     *   - 다른 서버의 L1은 건드리지 않음 — L1 TTL로 만료됨
     *   - 삭제가 실패해도 예외는 밖으로 나가지 않음(그 키는 Redis TTL로 만료됨)
     *
     * - Redis 삭제 결과만 계측에 남김
     *   - 두 서버가 같이 보는 값은 Redis라, 그 삭제가 실패했는지가 관측 대상
     */
    public void evict(MyCache cache, String key) {
        if (cache.getType() != CacheType.LOCAL) {
            if (swallowRun(() -> redis(cache).evict(key))) {
                invalidationMetrics.evicted();
            } else {
                invalidationMetrics.evictFailed();
                log.warn("캐시 삭제 실패: {}:{} — Redis TTL({}) 안에 만료된다", cache.getName(), key, redisTtlOf(cache));
            }
        }
        if (cache.getType() != CacheType.REDIS) {
            swallowRun(() -> local(cache).evict(key));
        }
    }

    /**
     * - L1 통계는 Caffeine이 들고 있는 것을 그대로 읽어 온다
     *   - {@code recordStats()}가 켜져 있어 히트·미스·축출·엔트리 수를 캐시별로 안다
     *   - L1이 없는 캐시(REDIS)는 빈 통계를 돌려준다
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats localStats(MyCache cache) {
        Cache c = localCacheManager.getCache(cache.getName());
        if (c instanceof org.springframework.cache.caffeine.CaffeineCache caffeine) {
            return caffeine.getNativeCache().stats();
        }
        return com.github.benmanes.caffeine.cache.stats.CacheStats.empty();
    }

    /** 현재 L1에 들어 있는 엔트리 수(추정). L1이 없는 캐시는 0. */
    public long localSize(MyCache cache) {
        Cache c = localCacheManager.getCache(cache.getName());
        if (c instanceof org.springframework.cache.caffeine.CaffeineCache caffeine) {
            return caffeine.getNativeCache().estimatedSize();
        }
        return 0L;
    }

    /** L2에 이 키가 실제로 올라가 있는가(적재 확인용). */
    public boolean existsInL2(MyCache cache, String key) {
        if (cache.getType() == CacheType.LOCAL) {
            return false;
        }
        Cache c = swallow(() -> redis(cache));
        return c != null && swallow(() -> c.get(key)) != null;
    }

    private static Object redisTtlOf(MyCache cache) {
        return cache instanceof MyCache.TwoTierCache twoTier ? twoTier.getRedisTtl() : cache.getTtl();
    }

    private Cache local(MyCache cache) {
        return localCacheManager.getCache(cache.getName());
    }

    private Cache redis(MyCache cache) {
        return redisCacheManager.getCache(cache.getName());
    }

    /**
     * - 캐시 조회 과정에서 발생한 예외는 외부로 전파하지 않음 - 캐시 장애가 발생해도 본 요청이 실패하지 않도록 처리 - 예외는 로그로만 남김
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

    /**
     * - 반영 실패를 삼키되, 성공했는지는 돌려준다
     *   - 삼키는 이유는 캐시 장애로 본 요청이 죽지 않게 하려는 것
     *   - 삭제 경로는 실패했다는 사실을 계측에 남겨야 해서 결과를 알려준다
     */
    private boolean swallowRun(Runnable runnable) {
        try {
            runnable.run();
            return true;
        } catch (Exception e) {
            log.warn("캐시 반영 실패, 건너뛴다", e);
            return false;
        }
    }


}
