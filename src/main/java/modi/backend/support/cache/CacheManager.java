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
    private final CacheInvalidationMetrics invalidationMetrics; // 조용한 실패를 남기지 않기 위한 계측
    private final CacheLookupMetrics lookupMetrics; // 계층별 히트/미스 — 관리자 대시보드의 히트율 출처

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
                lookupMetrics.l1Hit(cache);
                return v1; // 1. L1 hit
            }
            T v2 = swallow(() -> redis(cache).get(key, clazz));
            if (v2 != null) {
                swallowRun(() -> local(cache).put(key, v2));    // 2 L2 Hit --→ L1 backfill
                lookupMetrics.l2Hit(cache);
                return v2;
            }
            lookupMetrics.miss(cache);
            return null; // 3. All Miss --→ getOrPut이 loader로
        }
        T v = swallow(() -> getByCache(cache).get(key, clazz));
        if (v != null) {
            lookupMetrics.l2Hit(cache);
        } else {
            lookupMetrics.miss(cache);
        }
        return v;
    }

    /**
     * - L1 통계는 Caffeine이 들고 있는 것을 그대로 읽어 온다
     *   - {@code recordStats()}가 켜져 있어 히트·미스·축출·엔트리 수를 캐시별로 안다
     *   - 등록되지 않은 이름이면 빈 통계를 돌려준다
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats localStats(MyCache cache) {
        Cache c = localCacheManager.getCache(cache.getName());
        if (c instanceof org.springframework.cache.caffeine.CaffeineCache caffeine) {
            return caffeine.getNativeCache().stats();
        }
        return com.github.benmanes.caffeine.cache.stats.CacheStats.empty();
    }

    /** 현재 L1에 들어 있는 엔트리 수(추정). */
    public long localSize(MyCache cache) {
        Cache c = localCacheManager.getCache(cache.getName());
        if (c instanceof org.springframework.cache.caffeine.CaffeineCache caffeine) {
            return caffeine.getNativeCache().estimatedSize();
        }
        return 0L;
    }

    /** L2에 이 키가 실제로 올라가 있는가(워밍·적재 확인용). */
    public boolean existsInL2(MyCache cache, String key) {
        Cache c = redis(cache);
        return c != null && swallow(() -> c.get(key)) != null;
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
     * - evict = L2 삭제 + 자기 L1 삭제 + 방송
     *   - 셋 중 무엇이 실패해도 예외는 밖으로 나가지 않음
     *   - 방송 결과만 계측에 남김
     */
    public void evict(MyCache cache, String key) {
        swallowRun(() -> redis(cache).evict(key));
        swallowRun(() -> local(cache).evict(key));
        publish(cache, key);
    }

    /**
     * - refresh = L2에 새 값 + 방송(전 서버 L1 evict). 배치 재적재용
     */
    public void refresh(MyCache cache, String key, Object value) {
        swallowRun(() -> redis(cache).put(key, value));
        publish(cache, key);
    }

    /**
     * - 방송 한 번 + 그 결과 계측
     *   - 실패해도 본 요청은 그대로 진행됨
     *
     * - 자동 재발행을 하지 않음
     *   - 워밍·조회수 배치가 6시간 안에 L2를 덮어써 오염이 수렴함
     *   - 급하면 관리자가 수동 워밍을 당김
     *   - 대기열을 두지 않은 근거는 이 실패 카운터가 0으로 유지되는지로 검증함
     */
    private void publish(MyCache cache, String key) {
        if (swallowRun(() -> redisPublisher.publish(cache.getName() + ":" + key))) {
            invalidationMetrics.published();
            return;
        }
        invalidationMetrics.publishFailed();
        log.warn("무효화 방송 실패: {}:{} — 다음 워밍까지 다른 서버 L1이 옛 값일 수 있다", cache.getName(), key);
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

    /**
     * - 반영 실패를 삼키되, 성공했는지는 돌려준다
     *   - 삼키는 이유는 캐시 장애로 본 요청이 죽지 않게 하려는 것
     *   - 하지만 무효화 경로는 실패했다는 사실을 알아야 대기열에 넣을 수 있다
     *   - 그래서 "예외는 밖으로 안 내보내되 결과는 알려주는" 형태다
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
