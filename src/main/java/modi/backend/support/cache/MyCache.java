package modi.backend.support.cache;

import java.time.Duration;

public sealed abstract class MyCache
permits MyCache.LocalCache, MyCache.RedisCache, MyCache.TwoTierCache {

    private final CacheType type;
    private final String description;
    private final Duration ttl;
    private final Class<?> valueType;

    protected MyCache(CacheType type, String description, Duration ttl, Class<?> valueType) {
        this.type = type;
        this.description = description;
        this.ttl = ttl;
        this.valueType = valueType;
    }

    /** 캐시 이름은 클래스 이름에서 얻는다 — 문자열 상수를 따로 관리하지 않고, 오타는 컴파일 에러가 된다. */
    public final String getName(){
        return getClass().getSimpleName();
    }

    public CacheType getType(){
        return type;
    }

    public String getDescription(){
        return description;
    }

    /** LOCAL·REDIS의 TTL이자, TWO_TIER의 L1 TTL. */
    public Duration getTtl(){
        return ttl;
    }
    /**
     * 이 캐시에 담기는 값의 타입. L2 직렬화기를 이 타입에 바인딩하기 위해 선언이 함께 들고 다닌다
     * (타입 정보 없이 저장하면 역직렬화가 Map을 돌려줘 캐스팅에서 터진다).
     */
    public Class<?> getValueType() {
        return valueType;
    }

    /**
     * ----------------------------------------------------------------------------------------------
     */
    public non-sealed abstract static class LocalCache extends MyCache {
        protected LocalCache(String description, Duration ttl, Class<?> valueType) {
            super(CacheType.LOCAL, description, ttl, valueType);
        }
    }

    public non-sealed abstract static class RedisCache extends MyCache {
        protected RedisCache(String description, Duration ttl, Class<?> valueType) {
            super(CacheType.REDIS, description, ttl, valueType);
        }
    }

    /** 여운 기본형: L1 TTL(ttl)과 L2 TTL(redisTtl)을 함께 선언한다. */
    public non-sealed abstract static class TwoTierCache extends MyCache {

        private final Duration redisTtl;

        protected TwoTierCache(String description, Duration localTtl, Duration redisTtl, Class<?> valueType) {
            super(CacheType.TWO_TIER, description, localTtl, valueType);
            this.redisTtl = redisTtl;
        }

        public Duration getRedisTtl() {
            return redisTtl;
        }
    }
}
