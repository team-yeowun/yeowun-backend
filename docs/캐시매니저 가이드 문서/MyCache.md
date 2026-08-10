# `MyCache` 계층 읽는 법 — sealed 계층과 선언 규약

캐시를 하나 추가하려면 이 파일만 이해하면 된다. 이 문서는 `MyCache`의 선언 한 줄 한 줄에 담긴 의도와,
**우리가 실제로 쓰는 분기가 무엇인지**를 정리한다.

## 대상 코드

```java
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
    public final String getName() {
        return getClass().getSimpleName();
    }

    public CacheType getType() { return type; }
    public String getDescription() { return description; }
    public Duration getTtl() { return ttl; }
    public Class<?> getValueType() { return valueType; }

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

        public Duration getRedisTtl() { return redisTtl; }
    }
}
```

> **먼저 알아둘 것**: 세 분기 중 **현재 실제로 쓰이는 것은 `TwoTierCache` 하나뿐이다.**
> 전시 캐시 8종이 전부 여기서 나온다. `LocalCache`·`RedisCache`는 "종류는 이 셋"이라는 계약을 세우기 위해
> 자리를 잡아 둔 것이고, 아직 상속한 구현체가 없다. 그래서 이 문서의 예시도 `TwoTierCache` 기준으로 읽는 편이 맞다.

---

## 1. 왜 `static` 중첩 클래스인가

- `LocalCache`/`RedisCache`/`TwoTierCache`는 `MyCache`의 **특정 인스턴스**에 접근할 필요가 없다.
  non-static 내부 클래스는 암묵적으로 외부 인스턴스 참조(`MyCache.this`)를 들고 다니는데, 여기서는 그 연결이 불필요하다.
  게다가 우리 선언은 전부 싱글턴(`INSTANCE`)이라 외부 인스턴스라는 개념 자체가 없다.
- 세 하위 타입을 부모 파일 안에 함께 두면 **계층 전체가 한 화면에 들어온다.** `permits` 절이 `MyCache.LocalCache`
  형태로 이를 참조하는 것도 같은 파일이기 때문이다.
- 부수 효과 하나: 같은 컴파일 단위(같은 파일)라서 사실 `permits` 절을 **생략해도 컴파일러가 목록을 추론한다.**
  그럼에도 적어 둔 이유는 "종류는 이 셋뿐"이라는 사실을 선언 첫 줄에서 읽히게 하기 위해서다.

## 2. 왜 `abstract`이고 왜 `non-sealed`인가 ⭐

**봉인 범위가 두 단계로 다르다는 점이 핵심이다.**

- 최상위 `MyCache`는 `sealed` — "직계 하위는 `LocalCache`·`RedisCache`·`TwoTierCache` 셋뿐"이라고 못박는다.
  캐시의 **종류(저장소 조합)** 를 고정하는 역할이다. 네 번째 종류를 만들려면 `permits` 줄을 반드시 고쳐야 한다.
- `TwoTierCache` 자체는 실제로 쓰이는 캐시가 아니다. 2단 캐시들의 공통 부모일 뿐이고,
  실제 캐시는 이를 상속한 이름 붙은 구체 클래스(`HomeBanners`, `ExhibitionDetail` …)다.

그래서:
- `abstract` — 이 클래스 자체를 인스턴스화할 일이 없으므로
- `non-sealed` — 아래에서 자유롭게 상속해 새 캐시를 추가할 수 있어야 하므로

### `non-sealed`는 편의가 아니라 **강제**다

"permits에 매번 등록하기 번거로워서"가 아니다. **`sealed`로 두면 아예 불가능하다.**

`sealed` 타입과 `permits` 대상은 **이름 없는 모듈(unnamed module)에서는 같은 패키지에 있어야 한다.**
우리 프로젝트에는 `module-info.java`가 없으므로 이 규칙이 그대로 적용되는데:

| 클래스              | 패키지                                          |
|------------------|----------------------------------------------|
| `MyCache`        | `modi.backend.support.cache`                 |
| `ExhibitionCache`| `modi.backend.application.exhibition.cache`  |

**패키지가 다르다.** 즉 `TwoTierCache`를 `sealed`로 두면 전시 캐시를 `permits`에 올릴 방법이 없고,
`final`이면 상속 자체가 막힌다. `non-sealed`만이 **support 계층이 도메인 캐시 이름을 모른 채로**
도메인이 자기 캐시를 선언하게 해 준다.

> support가 application을 알면 의존 방향이 뒤집힌다. 여기서 계층을 다시 여는 것은 편의가 아니라 **설계상 맞다.**

**요약**: 종류 레벨(`MyCache`)은 봉인, 구현 레벨(`TwoTierCache` 이하)은 개방 — "seal-then-reopen" 패턴이다.

## 3. 왜 생성자가 전부 `protected`인가

- `MyCache`도, 세 하위 타입도 모두 추상 클래스라 직접 인스턴스화될 일이 없다.
  오직 하위 클래스가 `super(...)`로 호출할 때만 쓰인다.
- `protected`는 "이 생성자는 상속해서 쓰는 용도지, 외부에서 호출하는 공개 API가 아니다"라는 의도를 코드로 드러낸다.
- 실제 캐시 선언은 여기서 한 겹 더 닫는다 — **생성자를 `private`으로 두고 `INSTANCE` 하나만 공개**한다.

```java
public static final class HomeBanners extends MyCache.TwoTierCache {
    public static final HomeBanners INSTANCE = new HomeBanners();

    private HomeBanners() {   // ← private. 인스턴스는 INSTANCE 하나뿐
        super("홈 배너 목록", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.Banners.class);
    }
}
```

캐시 선언은 **값이 아니라 이름표**다. 같은 캐시의 인스턴스가 둘 있을 이유가 없어서 싱글턴으로 고정한다.

## 4. 왜 하위 생성자가 `type`을 받지 않는가

- 부모 `MyCache` 생성자는 `(type, description, ttl, valueType)` 4개를 받는다.
- 그런데 `TwoTierCache`의 하위 클래스는 **정의상 무조건 `CacheType.TWO_TIER`** 다.
- 만약 `type`을 매번 파라미터로 받게 했다면, 2단 캐시를 선언하면서 실수로 `CacheType.REDIS`를 넘기는 버그가 생길 수 있다.
  그러면 `CacheManager`가 L1을 건너뛰고 Redis로만 라우팅하는데, **테스트로는 잘 안 잡히고 성능만 조용히 나빠진다.**
- 그래서 `type`은 각 하위 생성자가 내부에서 고정하고, 구현체마다 값이 달라지는 것만 파라미터로 노출한다.

| 분기             | 노출하는 파라미터                                    | 고정하는 값                |
|----------------|----------------------------------------------|-----------------------|
| `LocalCache`   | `description`, `ttl`, `valueType`            | `CacheType.LOCAL`     |
| `RedisCache`   | `description`, `ttl`, `valueType`            | `CacheType.REDIS`     |
| `TwoTierCache` | `description`, `localTtl`, `redisTtl`, `valueType` | `CacheType.TWO_TIER` |

`TwoTierCache`만 파라미터가 하나 많은 것이 **이 계층이 enum이 아닌 이유 그 자체**다(§7 참고).

## 5. 왜 `super(...)` 호출이 있는가

- `type`·`description`·`ttl`·`valueType`은 전부 `MyCache`에 `private final`이다.
- `private`이라 하위 클래스가 직접 초기화할 수 없다 — 오직 부모 생성자를 통해서만 값이 채워진다.
- `TwoTierCache`는 자기 필드(`redisTtl`)만 직접 두고, 나머지는 고정값 `CacheType.TWO_TIER`와 함께
  부모 생성자에 넘겨 초기화를 위임한다.

## 6. 필드별 존재 이유

| 필드            | 왜 있는가                                                                                    |
|---------------|------------------------------------------------------------------------------------------|
| `type`        | `CacheManager.getByCache()`가 저장소를 고르는 기준. L1만·L2만·2단을 여기서 가른다                            |
| `description` | 사람이 읽는 용도. **현재 코드에서 호출되는 곳은 없다** — 선언만 보고 "이게 무슨 캐시인지" 알게 하는 문서값이다                       |
| `ttl`         | LOCAL·REDIS의 TTL이자, TWO_TIER의 **L1 TTL**. `CacheConfig`가 Caffeine `expireAfterWrite`에 넣는다 |
| `redisTtl`    | `TwoTierCache`에만 있는 **L2 TTL**. jitter를 더해 Redis `entryTtl`로 들어간다                        |
| `valueType`   | **L2 직렬화기를 이 타입에 바인딩하기 위해** 선언이 들고 다닌다 ↓                                                 |

### `valueType`이 필요한 이유 (우리 프로젝트 고유)

블로그 원안에는 없던 필드다. 두 가지 때문에 추가했다.

1. 이 프로젝트는 Boot 4.1 / **Jackson 3(`tools.jackson`)** 이고, Jackson 2 쪽 `ObjectMapper`는 **빈이 없다.**
   그래서 블로그의 `GenericJackson2JsonRedisSerializer`를 그대로 쓸 수 없다.
2. 값 타입 정보 없이 저장하면 `Cache.get(key, clazz)`가 `LinkedHashMap`을 돌려줘 **`ClassCastException`** 이 난다.

그래서 선언이 값 타입을 들고 다니고, `CacheConfig`가 그 타입에 바인딩된 직렬화기를 캐시별로 붙인다.

```java
new JacksonJsonRedisSerializer<>(objectMapper, cache.getValueType())
```

**주의**: 선언의 `valueType`과 호출부의 `getOrPut(..., clazz, ...)`가 **같은 타입이어야 한다.**
어긋나면 컴파일은 통과하고 L2 역직렬화에서 터진다.

## 7. `getName()`이 `final`인 이유

```java
public final String getName() {
    return getClass().getSimpleName();
}
```

캐시 이름은 **Caffeine 캐시 이름이자 Redis 키의 일부**(`yeowun:HomeBanners::ALL`)이고,
3편의 무효화 방송 메시지 포맷(`"캐시이름:엔트리키"`)에도 그대로 들어간다.

하위 타입이 이걸 오버라이드하면 **한 캐시가 두 이름을 갖게 되어 L1과 L2가 조용히 갈라진다.**
`final`로 막아 그 경로를 없앴다. 덕분에 선언을 추가하면 이름이 함께 생기고, 이름을 오타 내면 컴파일이 되지 않는다.

## 8. enum이 아니라 sealed인 이유 (설계 배경)

이 목록에 enum이 먼저 떠오르는 것이 자연스럽다. 실제로 그렇게 시작했다가 방향을 틀었다.

**막힌 곳은 TTL이었다.** 로컬·Redis 캐시는 TTL이 하나면 되지만 2단 캐시는 두 개(L1·L2)가 필요하다.
enum은 **모든 상수가 같은 필드 집합을 공유**하므로, `redisTtl`이 단층 캐시 상수에도 딸려 오고 거기서는 의미 없는 `null`이 된다.

> null이 되는 필드는 규칙이 코드 밖으로 새어 나갔다는 신호다.
> "이 값은 2단일 때만 유효하다"가 타입이 아니라 주석에 남고, 확인을 빠뜨려도 컴파일은 통과한다.

우리가 원한 것은 반대였다 — **L2 TTL을 읽으려면 그것이 2단 캐시임이 먼저 증명되어야 하고,
증명하지 못했다면 그 메서드가 아예 보이지 않는 것.** `getRedisTtl()`이 `TwoTierCache` 안에만 있는 이유다.

```java
// CacheConfig — 타입을 좁힌 뒤에야 getRedisTtl()을 부를 수 있다. 이 좁히기가 곧 증명이다.
if (cache instanceof MyCache.TwoTierCache twoTier) {
    Duration ttl = twoTier.getRedisTtl().plusMinutes(/* jitter */);
    …
}
```

**대신 포기한 것도 있다.** enum이었다면 `values()`가 전체 목록을 공짜로 줬을 텐데, 우리는 `ExhibitionCache.ALL`을 직접 유지한다.

## 9. ⚠️ 캐시를 추가할 때 꼭 지킬 것

```java
/** 조립(CacheConfig)이 순회할 전체 선언 목록. */
public static final List<MyCache> ALL = List.of(
        HomeBanners.INSTANCE, HomeEndingSoon.INSTANCE, HomeFree.INSTANCE, HomeNewThisMonth.INSTANCE,
        ExploreLatestP1.INSTANCE, ExploreEndingP1.INSTANCE, ExplorePopularP1.INSTANCE,
        ExhibitionDetail.INSTANCE);
```

**선언을 추가하고 `ALL`에 넣는 것을 잊으면 그 캐시는 등록되지 않는다. 컴파일러가 잡아 주지 않는다.**
`CaffeineCacheManager`는 등록되지 않은 이름으로 `getCache()`를 부르면 **TTL도 크기 상한도 없는 기본 캐시를 즉석에서 만들어 버려서**,
동작은 하는데 만료가 안 되는 상태가 된다. 조용히 잘못되는 종류의 실수라 리뷰에서 반드시 확인한다.

sealed 계층이 보장하는 것과 아닌 것을 구분해 두면 헷갈리지 않는다.

| 질문                        | 보장 | 근거                                  |
|---------------------------|----|-------------------------------------|
| 캐시 *종류*가 셋뿐인가             | ✅  | `MyCache` sealed + `permits`         |
| 캐시 *선언* 목록을 다 아는가         | ❌  | 세 분기가 `non-sealed`라 아래는 열려 있다       |
| `ALL` 누락을 잡아 주는가          | ❌  | **직접 확인해야 한다**                      |

---

## 캐시 하나 추가하는 전체 절차

```java
// 1) ExhibitionCache에 선언 추가 — 바뀌는 유일한 파일
public static final class HomeFree extends MyCache.TwoTierCache {
    public static final HomeFree INSTANCE = new HomeFree();

    private HomeFree() {
        super("무료 전시", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.ListPage.class);
        //     설명            L1 TTL            L2 TTL             값 타입(직렬화 바인딩)
    }
}

// 2) ALL 목록에 INSTANCE 한 줄 추가  ← 잊으면 등록 안 됨(§9)

// 3) 호출부에서 사용
cacheManager.getOrPut(ExhibitionCache.HomeFree.INSTANCE, "ALL",
        ExhibitionResult.ListPage.class, loader);   // ← 선언의 valueType과 같은 타입이어야 함
```

`CacheManager`·`RedisPublisher`·`CacheConfig`는 **전부 그대로다.**
2단 조회·실패 폴백·무효화 방송·TTL jitter가 새 캐시에도 자동으로 적용된다.

---

## 한눈에 보는 요약표

| 구성 요소            | 선택                            | 이유                                                  |
|------------------|-------------------------------|-----------------------------------------------------|
| `sealed`         | `MyCache`에 지정                  | 캐시 **종류**를 로컬·Redis·2단 셋으로 고정                        |
| `static`         | 중첩 클래스에 지정                     | 외부 인스턴스 참조 불필요, 계층을 한 파일에 응집                        |
| `abstract`       | 세 분기에 지정                       | 실제 사용 캐시가 아닌 공통 부모, 직접 인스턴스화 방지                     |
| `non-sealed`     | 세 분기에 지정                       | **패키지가 달라 sealed로는 불가능**. 도메인이 자기 캐시를 선언하려면 열어야 한다 |
| `protected` 생성자  | `MyCache` + 세 분기               | 상속 전용, 외부 공개 API가 아님을 명시                            |
| `private` 생성자    | 실제 선언(`HomeBanners` 등)         | 인스턴스는 `INSTANCE` 하나뿐 — 선언은 값이 아니라 이름표               |
| `type` 미노출       | 하위 생성자가 고정                     | 2단 캐시에 `REDIS`를 넘기는 실수를 원천 차단                       |
| `valueType`      | 선언이 값 타입을 들고 다님                | Jackson 3 + `Class<T>` 역직렬화 바인딩(§6)                 |
| `final getName()`| 오버라이드 금지                       | 캐시 이름 = Caffeine 이름 = Redis 키 = 방송 메시지(§7)          |
| `ALL` 수동 유지      | enum `values()`를 포기한 대가        | **누락을 컴파일러가 못 잡는다**(§9)                             |
