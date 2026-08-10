# 캐시 선언은 왜 enum이 아니라 sealed class인가

> 요약: 이 문서에서는 여운의 캐시 선언 계층(`MyCache`)에 Java 21의 `sealed` 클래스와 패턴 매칭을 적용한 과정을
> 공유합니다. enum으로 시작했다가 방향을 튼 이유부터 `permits`와 `non-sealed`의 선택 기준, 실제 코드에서
> 패턴 매칭이 쓰이는 자리와 이 구조가 보장하지 못하는 것까지 순서대로 다루며, 닫힌 계층을 언제 선택해야 하는지를
> 함께 정리합니다.

---

## 시작하며

여운의 캐시는 선언 하나로 추가할 수 있게 설계되어 있습니다. 캐시 이름과 저장소 종류, TTL, 값 타입을
`MyCache`를 상속한 클래스 하나에 적어 두면 2단 조회와 실패 폴백, 무효화 방송이 자동으로 따라옵니다.

그런데 이 선언 계층을 처음 만들 때는 enum으로 시작했습니다. 캐시 목록은 값이 미리 정해진 상수 집합처럼
보였고, enum은 그런 목록에 가장 잘 맞는 문법이기 때문입니다. 하지만 구현을 진행하면서 enum으로는 표현할 수 없는
지점을 만났고, 결국 `sealed` 클래스로 옮기게 되었습니다.

이번 문서에서는 그 판단의 과정과 결과를 코드와 함께 살펴보겠습니다. 먼저 enum이 막힌 지점을 확인하고,
저희 계층이 어떤 모양으로 닫히고 열리는지 본 다음, 패턴 매칭이 실제로 쓰이는 자리와 이 구조가 보장하지 못하는
부분까지 구체적으로 설명드리겠습니다.

대상 코드는 `modi.backend.support.cache.MyCache`와 `CacheManager`,
그리고 `modi.backend.application.exhibition.cache.ExhibitionCache`입니다.

---

## enum이 막힌 지점

결론부터 말씀드리면, 저희 캐시는 **종류마다 들고 다녀야 할 값이 다르기 때문에** enum으로 표현할 수 없었습니다.

캐시 선언이 들고 있어야 하는 값은 이름과 저장소 종류, TTL, 그리고 값 타입입니다. 여기까지는 enum으로도 충분합니다.
문제는 TTL이었습니다. 로컬 캐시와 Redis 캐시는 TTL이 하나면 되지만, 저희 기본형인 2단 캐시는 L1 TTL과 L2 TTL
두 개가 필요합니다.

> **Java의 enum이란?**
> 값이 미리 정해진 상수 집합을 하나의 타입으로 묶는 문법입니다. 각 상수는 유일한 인스턴스로 딱 하나만 존재하고,
> 필드와 메서드를 가질 수 있으며, `values()`로 전체 목록을 얻을 수 있습니다.
> 다만 **모든 상수가 같은 필드 집합을 공유합니다.** 어떤 상수에만 필요한 값이 있어도 나머지 상수에 그 자리가 그대로 남습니다.

이 성질 때문에 2단 캐시의 `redisTtl`을 enum으로 표현하면 단층 캐시 상수에도 그 필드가 딸려 오고,
거기서는 아무 의미 없는 `null`이 됩니다. null이 되는 필드는 규칙이 코드 밖으로 새어 나갔다는 신호입니다.
"이 값은 2단일 때만 유효하다"는 문장이 타입이 아니라 주석에 남고, 그 필드를 읽는 쪽은 매번 타입을 먼저 확인해야 하며,
확인을 빠뜨려도 컴파일은 통과하기 때문입니다.

저희가 원한 것은 그 반대였습니다. **L2 TTL을 읽으려면 그것이 2단 캐시라는 사실이 먼저 증명되어야 하고,
증명하지 못했다면 그 메서드가 아예 보이지 않는 것**입니다. 이러한 요구는 상수의 목록으로는 표현할 수 없고
타입의 계층으로만 표현할 수 있어서, 저희는 `sealed` 클래스를 선택했습니다.

> **sealed class란?**
> Java 17에 정식으로 들어온 문법으로, `permits`에 적은 타입만 상속할 수 있도록 계층을 닫아 둡니다.
> 하위 타입이 무엇인지 컴파일 시점에 고정되므로 상속이 예상 밖으로 늘어나지 않고, 타입을 분기하는 코드가
> 케이스를 빠뜨렸는지도 컴파일러가 검사할 수 있습니다. enum이 "값의 닫힌 집합"이라면
> sealed class는 **"종류의 닫힌 집합"**입니다. 하위 타입마다 필드와 메서드가 달라도 됩니다.

한 가지 짚어 두면, `sealed`는 상속 관계를 제한하는 언어 기능일 뿐 인스턴스 생성 권한이나 값의 유효성을
대신 검사해 주지는 않습니다. 저희 선언이 `private` 생성자와 `INSTANCE` 싱글턴으로 되어 있는 것은
그와 별개로 설계한 부분입니다.

그렇다면 저희 계층은 실제로 어떤 모양일까요? 다음 절에서 살펴보겠습니다.

---

## 저희 계층의 모양

저희 계층은 **위쪽은 닫혀 있고 아래쪽은 열려 있는** 두 단계 구조입니다.

```
MyCache (sealed abstract)          ← 종류는 이 셋뿐이라고 못박는 지점
├── LocalCache      (non-sealed)   ← 여기부터 다시 열립니다
├── RedisCache      (non-sealed)
└── TwoTierCache    (non-sealed)   + redisTtl, getRedisTtl()
        ↑
        └── ExhibitionCache.HomeBanners / HomeFree / ExhibitionDetail 등 8종 (다른 패키지)
```

위쪽이 닫혀 있다는 것은 캐시의 **종류**가 로컬과 Redis, 2단 셋뿐이라는 뜻입니다. 네 번째 종류를 만들려면
`permits` 줄을 반드시 고쳐야 하므로, 저장소 조합이 조용히 늘어날 수 없습니다.

반면 아래쪽이 열려 있다는 것은 각 종류 밑에 **어떤 캐시가 몇 개 있는지**는 제한하지 않는다는 뜻입니다.
전시가 8종을 선언하든, 나중에 기록이나 알림 도메인이 자기 캐시를 선언하든 `MyCache`는 손대지 않습니다.

이 두 단계가 저희가 의도한 성질입니다. 저장소 조합은 support 계층이 통제하고, 캐시 목록은 각 도메인이 늘리는
구조이기 때문에, 캐시를 하나 추가할 때 고치는 파일이 선언 하나로 유지됩니다.

이제 이 구조가 코드로 어떻게 적혀 있는지 확인해 보겠습니다.

---

## 선언 문법과 permits

우선 최상위 `MyCache`를 살펴보겠습니다. `MyCache` 클래스는 캐시의 정체(이름과 타입, TTL, 값 타입)를
보관하고, 하위 타입들이 공유하는 조회 메서드를 제공하는 역할을 합니다.

```java
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

    /** 캐시 이름은 클래스 이름에서 얻습니다. 문자열 상수를 따로 관리하지 않고, 오타는 컴파일 에러가 됩니다. */
    public final String getName() {
        return getClass().getSimpleName();
    }

    public CacheType getType() { return type; }
    public Duration getTtl() { return ttl; }
    public Class<?> getValueType() { return valueType; }
}
```

이 선언에서 눈여겨볼 부분은 세 가지입니다.

1. **`permits`에는 직접 하위 타입만 적습니다.** `ExhibitionCache.HomeBanners`는 `TwoTierCache`를 상속한
   손자이므로 이 목록에 들어가지 않습니다. 중간 타입을 건너뛴 손자 목록을 적는 자리가 아닙니다.
2. **`permits` 절은 사실 생략할 수 있습니다.** 세 하위 타입이 같은 파일 안의 중첩 클래스라 컴파일러가 목록을
   추론할 수 있기 때문입니다. 그럼에도 명시한 이유는 "종류는 이 셋뿐"이라는 사실이 선언 첫 줄에서 읽히도록
   하기 위해서입니다.
3. **`getName()`이 `final`입니다.** 캐시 이름은 Caffeine 캐시 이름이자 Redis 키의 일부이고, 무효화 방송
   메시지에도 그대로 들어갑니다. 하위 타입이 이 메서드를 덮어쓰면 한 캐시가 두 이름을 갖게 되어 L1과 L2가
   조용히 갈라지므로, `final`로 그 경로를 막아 두었습니다.

그렇다면 하위 세 타입은 왜 모두 `non-sealed`일까요? 이 부분은 취향의 문제가 아니라서 따로 살펴보겠습니다.

---

## 왜 셋 다 non-sealed인가

먼저 세 가지 선택지가 각각 무엇을 뜻하는지 정리하면 다음과 같습니다.

| 직접 하위 클래스 선언 | 그 아래 확장       | 선택 기준            |
|-------------|---------------|------------------|
| `final`     | 더 이상 확장 불가    | 해당 가지를 완결할 때     |
| `sealed`    | 새 허용 목록으로 계속 제한 | 하위 단계도 닫힌 종류로 관리할 때 |
| `non-sealed`| 다시 자유롭게 확장 가능 | 특정 가지부터 확장을 열 때  |

저희가 `non-sealed`를 고른 이유는 편의 때문이 아닙니다. **다른 선택지로는 애초에 컴파일이 되지 않기 때문입니다.**

`sealed` 타입과 `permits` 대상은 이름 있는 모듈에서는 같은 모듈에, 이름 없는 모듈에서는 **같은 패키지에**
있어야 합니다. 저희 프로젝트에는 `module-info.java`가 없으므로 이름 없는 모듈이고, 따라서 같은 패키지 제약이
그대로 적용됩니다. 그런데 두 클래스의 위치는 다음과 같습니다.

| 클래스               | 패키지                                         |
|-------------------|---------------------------------------------|
| `MyCache`         | `modi.backend.support.cache`                |
| `ExhibitionCache` | `modi.backend.application.exhibition.cache` |

패키지가 서로 다르기 때문에, `TwoTierCache`를 `sealed`로 두면 전시 캐시를 `permits`에 올릴 방법이 없고
`final`로 두면 상속 자체가 막힙니다. `non-sealed`만이 support 계층이 도메인 캐시의 이름을 모르는 상태로
도메인이 자기 캐시를 선언하게 해 줍니다.

이는 설계 측면에서도 맞는 선택입니다. support가 application을 알게 되면 의존 방향이 뒤집히기 때문에,
이 지점에서 계층을 다시 여는 편이 오히려 자연스럽습니다.

정리하면 종류 레벨은 봉인하고 구현 레벨은 개방하는 형태이며, 이를 seal-then-reopen 패턴이라고 부릅니다.
이러한 구조 덕분에 저장소 조합의 안정성과 캐시 추가의 자유로움을 함께 얻을 수 있습니다.

다음으로는 이렇게 닫아 둔 계층을 실제 코드가 어떻게 활용하는지 보겠습니다.

---

## instanceof 패턴 매칭이 쓰이는 자리

앞에서 말씀드린 "증명"이 코드로 나타나는 곳이 `CacheConfig`입니다. `getRedisTtl()`은 `TwoTierCache`
안에만 있어서, 타입을 좁히기 전에는 존재조차 하지 않습니다.

> **instanceof 패턴 매칭이란?**
> Java 16에 정식으로 들어온 문법으로, 타입 검사와 형 변환, 지역 변수 선언을 하나의 타입 패턴으로 묶습니다.
> `if (o instanceof String s)`처럼 쓰면 검사에 성공한 흐름에서 `s`를 바로 사용할 수 있습니다.
> 패턴 변수는 매칭 성공이 보장되는 코드 흐름에서만 쓸 수 있고, `null`에는 매칭되지 않습니다.

다음은 L2 캐시 설정을 조립하는 코드입니다.

```java
for (MyCache cache : ExhibitionCache.ALL) {
    if (cache instanceof MyCache.TwoTierCache twoTier) {   // 이 좁히기가 곧 증명입니다
        Duration ttl = twoTier.getRedisTtl()               // 좁히기 전에는 부를 방법이 없습니다
                .plusMinutes(ThreadLocalRandom.current().nextLong(0, JITTER_MAX_MINUTES));
        builder.withCacheConfiguration(cache.getName(),
                RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith("yeowun:")
                        .entryTtl(ttl)
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(objectMapper, cache.getValueType()))));
    }
}
```

`instanceof` 패턴이 타입 검사와 형 변환, 변수 선언을 한 번에 처리하기 때문에 예전 방식의
`(MyCache.TwoTierCache) cache` 캐스팅이 사라집니다. 그리고 좁혀진 변수 `twoTier`를 통해서만
`getRedisTtl()`에 접근할 수 있으므로, 단층 캐시에 대고 L2 TTL을 읽으려는 코드는 작성 자체가 불가능합니다.

만약 enum이었다면 `getRedisTtl()`이 모든 상수에 보이고, 단층 캐시에 대고 불러도 컴파일러는 아무 말을 하지
않았을 것입니다. 이를 통해 규칙 위반을 런타임이 아니라 컴파일 시점에 차단할 수 있습니다.

여기서 `switch`가 아니라 `if`를 쓴 이유도 있습니다. 이 코드의 관심사는 "2단이냐 아니냐" 하나뿐이라 분기가
둘이기 때문입니다. 타입을 한두 개만 확인할 때는 `instanceof`가 더 간결합니다.

그렇다면 여러 종류로 분기해야 할 때는 어떻게 될까요? 다음 절에서 `switch` 패턴 매칭을 살펴보겠습니다.

---

## switch 패턴 매칭과 완전성 검사

`CacheManager`에서 저장소를 고르는 `getByCache` 메서드는 다음과 같습니다.

```java
private Cache getByCache(MyCache cache) {
    return switch (cache.getType()) {       // MyCache가 아니라 CacheType(enum)을 스위치합니다
        case LOCAL -> local(cache);
        case REDIS, TWO_TIER -> redis(cache);
    };
}
```

`getByCache` 메서드는 선언이 들고 있는 `CacheType`을 읽어 실제 저장소를 결정하는 역할을 합니다.
`default` 분기가 없는데도 컴파일이 되는데, 여기서 한 가지 주의할 점이 있습니다.

**이 코드에서 완전성을 보장하는 것은 `sealed`가 아니라 `CacheType`이 enum이라는 사실입니다.**
스위치하는 대상이 `cache`가 아니라 `cache.getType()`이기 때문입니다. `sealed` 계층은 이 코드에 관여하지 않습니다.
혼동하기 쉬운 지점이라 코드 주석에도 그렇게 적어 두었습니다.

같은 일을 타입 패턴으로 작성하면 다음과 같은 모양이 됩니다. 현재 코드는 위쪽이며, 아래는 비교를 위한 예시입니다.

```java
private Cache getByCache(MyCache cache) {
    return switch (cache) {
        case MyCache.LocalCache local -> local(cache);
        case MyCache.RedisCache redis -> redis(cache);
        case MyCache.TwoTierCache twoTier -> redis(cache);
    };
}
```

이쪽도 `default` 없이 완전합니다. `MyCache`가 `sealed`이고 허용된 세 타입을 모두 처리했기 때문입니다.
`non-sealed` 가지 아래에 `HomeBanners` 같은 구체 캐시가 아무리 늘어나도, 상위 타입 패턴 하나가 그 가지 전체를
포괄하므로 분기가 함께 늘어나지는 않습니다.

두 방식을 비교하면 다음과 같습니다.

| 항목        | 현재(enum switch)                     | 대안(타입 switch)                  |
|-----------|-------------------------------------|-------------------------------|
| 완전성의 출처   | `CacheType` enum                    | `MyCache` sealed              |
| 새 종류 추가 시 | `CacheType` 상수 추가 후 이 switch가 컴파일 에러 | `permits` 추가 후 이 switch가 컴파일 에러 |
| 타입 정보 활용  | 불가능(좁혀진 변수가 없음)                     | 가능(`twoTier.getRedisTtl()` 등) |
| 판단        | 저장소 라우팅만 필요하므로 현재는 이 방식으로 충분        | 분기마다 하위 타입 필드가 필요해지면 전환       |

어느 방식을 쓰든 **`default`를 습관적으로 넣지 않는 것이 중요합니다.** `default`를 넣으면 문법적인 완전성은
만족하지만, 새로운 종류가 추가되었을 때 컴파일러가 침묵하기 때문입니다. 그 경우 새 저장소가 검토 없이 Redis로
라우팅되는 논리적 허점이 생길 수 있습니다.

---

## 이 구조가 보장하는 것과 보장하지 않는 것

저희 계층은 `sealed`이지만 하위 세 타입이 모두 `non-sealed`입니다. 따라서 보장 범위를 정확히 알아 두어야 합니다.

| 질문                          | 보장 여부 | 근거                                       |
|-----------------------------|-------|------------------------------------------|
| 캐시 종류가 로컬·Redis·2단 셋뿐인가     | 보장됨   | `MyCache`가 sealed이고 `permits`가 셋          |
| `MyCache` 하위 인스턴스 목록을 다 아는가 | 보장 안 됨 | 세 타입이 `non-sealed`라 아래가 열려 있음            |
| 새 종류 추가 시 분기 누락을 잡아 주는가     | 보장됨   | 현재는 `CacheType` enum이 그 역할을 합니다          |
| 새 캐시 선언 추가를 잡아 주는가          | 보장 안 됨 | `ExhibitionCache.ALL`에 직접 넣어야 합니다        |

마지막 줄이 저희가 의식적으로 치른 대가입니다. enum이었다면 `values()`가 전체 목록을 공짜로 주었겠지만,
`sealed`로 옮기면서 전체 목록을 직접 유지하게 되었습니다.

```java
/** 조립(CacheConfig)이 순회할 전체 선언 목록입니다. */
public static final List<MyCache> ALL = List.of(
        HomeBanners.INSTANCE, HomeEndingSoon.INSTANCE, HomeFree.INSTANCE, HomeNewThisMonth.INSTANCE,
        ExploreLatestP1.INSTANCE, ExploreEndingP1.INSTANCE, ExplorePopularP1.INSTANCE,
        ExhibitionDetail.INSTANCE);
```

**선언을 추가하고 이 목록에 넣는 것을 잊으면 그 캐시는 등록되지 않으며, 컴파일러가 잡아 주지 않습니다.**
게다가 `CaffeineCacheManager`는 등록되지 않은 이름으로 `getCache()`를 부르면 TTL도 크기 상한도 없는
기본 캐시를 즉석에서 만들어 버립니다. 동작은 하는데 만료가 되지 않는 상태가 되므로, 리뷰에서 반드시 확인해야 하는
부분입니다.

짧은 선언과 자동 목록을 내주고 종류마다 다른 모양을 가질 자유를 받은 교환이라고 이해하시면 됩니다.

---

## record 패턴은 캐시 값에 적용됩니다

`MyCache`는 클래스라서 record 패턴의 대상이 아니지만, **캐시에 담기는 값**은 전부 record입니다.
`ExhibitionResult.Banners`와 `ExhibitionResult.ListPage`, `ExhibitionResult.Detail`이 여기에 해당합니다.

> **record 패턴이란?**
> Java 21에 정식으로 들어온 문법으로, 타입 확인과 컴포넌트 추출을 한 번에 수행합니다.
> `case Paid(var id, var total)`처럼 쓰면 타입을 확인하면서 동시에 내부 값을 꺼낼 수 있고, 중첩된 레코드도
> 다시 분해할 수 있습니다.

캐시에서 꺼낸 값을 지표용으로 요약한다고 가정하면 다음과 같이 작성할 수 있습니다.

```java
String summarize(Object cached) {
    return switch (cached) {
        case ExhibitionResult.Banners(var items) -> "배너 " + items.size() + "건";
        case ExhibitionResult.ListPage page -> "목록 " + page.content().size() + "/" + page.totalCount();
        case ExhibitionResult.Detail detail -> "상세 " + detail.exhibitionId();
        case null -> "없음";
        default -> "알 수 없는 캐시 값";
    };
}
```

여기서는 `Object`를 스위치하므로 닫힌 계층이 아니며, 따라서 `default`가 반드시 필요합니다.
앞 절의 `getByCache`와 정반대 상황이라 `default`가 있는 편이 옳습니다.

또 한 가지 참고하실 점은 `ListPage`처럼 컴포넌트가 네 개인 record는 전부 분해하면 오히려 읽기 어려워진다는
것입니다. 위 코드에서 `Banners`만 분해하고 나머지는 타입 패턴으로 받아 접근자를 쓴 이유가 여기에 있습니다.
분해는 컴포넌트를 두 개 이상 동시에 사용할 때 값을 합니다.

---

## 가드와 case 순서

`ExhibitionCacheKeyResolver`는 패턴 `switch`가 아니라 `if` 체인으로 되어 있지만, 배치 원칙은 동일합니다.

```java
// 좁고 구체적인 조건에서 넓은 조건 순서로 배치합니다
if (section == ExhibitionSection.ENDING_SOON && sort == ExhibitionSort.LATEST) { … }
if (section == ExhibitionSection.OPENING_THIS_MONTH && sort == ExhibitionSort.LATEST) { … }
if (section == ExhibitionSection.FREE && sort == ExhibitionSort.LATEST) { … }
if (section != null) {
    return Optional.empty();     // 넓은 조건입니다. 위 셋보다 먼저 오면 세 분기가 죽습니다
}
return switch (sort) { … };
```

같은 로직을 패턴 `switch`로 옮긴다면 `when` 가드가 이 역할을 맡게 됩니다.

```java
return switch (section) {
    case ENDING_SOON when sort == ExhibitionSort.LATEST -> Optional.of(HomeEndingSoon.INSTANCE);
    case OPENING_THIS_MONTH when sort == ExhibitionSort.LATEST -> Optional.of(HomeNewThisMonth.INSTANCE);
    case FREE when sort == ExhibitionSort.LATEST -> Optional.of(HomeFree.INSTANCE);
    case null -> exploreCacheOf(sort);      // 섹션이 없으면 탐색 목록입니다
    default -> Optional.empty();            // 섹션과 다른 정렬의 조합입니다
};
```

여기서 지켜야 할 원칙은 세 가지입니다.

1. **`when` 가드는 타입이나 상수 매칭이 성공한 뒤에 평가됩니다.** 그래서 매칭된 값을 조건식에서 바로 쓸 수 있습니다.
2. **구체적인 패턴을 넓은 패턴보다 먼저 둡니다.** 앞 분기가 뒤 분기를 항상 가리면 컴파일러가 지배 관계 오류로
   거부하기 때문입니다.
3. **가드에는 짧고 부작용 없는 조건만 둡니다.** 가드 안에서 예외가 발생하면 그대로 전파되기 때문입니다.

---

## null 처리 경계

저희 `CacheManager`는 **null을 캐시 미스라는 정상 신호로 사용합니다.** 그래서 null 경계가 설계의 일부입니다.

```java
private <T> T swallow(Supplier<T> supplier) {
    try {
        return supplier.get();
    } catch (Exception e) {
        log.warn("캐시 조회 실패, DB로 폴백한다", e);
        return null;              // 장애를 미스와 같은 모양으로 만듭니다
    }
}
```

`swallow` 메서드는 캐시 계층에서 발생한 예외를 삼켜 `null`로 바꾸는 역할을 합니다. 이를 통해 캐시 장애가
미스와 같은 흐름을 타게 되고, 호출부인 `getOrPut`이 자연스럽게 loader로 폴백할 수 있습니다.

표현별 null 동작은 다음과 같습니다.

| 표현                                | `null` 입력 결과           | 저희 코드에서의 의미                       |
|-----------------------------------|------------------------|-----------------------------------|
| `cache instanceof TwoTierCache t` | `false`                | `CacheConfig`가 안전하게 건너뜁니다         |
| 패턴 `switch`에 `case null`이 없을 때    | `NullPointerException` | 애초에 null을 넘기지 않는 계약입니다            |
| 패턴 `switch`에 `case null`이 있을 때    | 해당 분기 실행               | 리졸버의 `case null`(섹션 없음)이 정상 상태입니다 |

한 가지 사례를 공유드리면, 이 null 체크에서 실제로 사고가 있었습니다. `getOrPut`에서 `if (cache != null)`과
`if (cached != null)`은 한 글자 차이인데, 전자로 쓰면 파라미터를 검사하게 되어 **항상 참**이 됩니다.
그 결과 loader가 영원히 호출되지 않고 캐시 미스마다 `null`이 반환되었습니다. `CacheManagerTest`의 폴백
테스트가 이 문제를 잡아 주었습니다.

---

## 다형성과 패턴 매칭은 함께 씁니다

`sealed`와 다형성은 경쟁 관계가 아니며, 저희 코드에는 둘이 나란히 있습니다.

| 관심사               | 방식    | 저희 코드                                        |
|-------------------|-------|----------------------------------------------|
| 캐시 이름을 어떻게 얻는가    | 다형성   | `MyCache.getName()`이 모든 종류에 같은 규칙을 적용합니다     |
| 저장소를 어디로 보내는가     | 분기    | `CacheManager.getByCache()`가 호출자 입장에서 결정합니다  |
| L2 TTL을 어떻게 조립하는가 | 패턴 매칭 | `CacheConfig`가 `instanceof`로 타입을 좁혀 읽습니다     |

판단 기준은 **그 동작이 타입의 본질인지, 호출자의 해석인지**입니다. 이름을 짓는 일은 캐시 자신의 본질이라
`MyCache`가 갖고 있고, 저장소 라우팅과 TTL 조립은 창구와 조립부의 관심사라 밖에서 분기합니다.

`getRedisTtl()`을 `MyCache`로 올려서 "2단이 아니면 예외를 던진다"는 식으로 만들지 않은 이유가 여기에 있습니다.
그렇게 하면 컴파일 시점에 막을 수 있는 규칙을 런타임으로 미루게 되기 때문입니다.

---

## Spring 설정에 적용하기

지금까지 본 요소들이 실제로 조립되는 곳이 `CacheConfig`입니다. 다음은 캐시 매니저 두 개를 만드는 코드입니다.

```java
@Configuration
public class CacheConfig {

    private static final long LOCAL_MAX_SIZE = 1_000L;
    private static final long JITTER_MAX_MINUTES = 30L;

    @Bean
    public CaffeineCacheManager localCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        for (MyCache cache : ExhibitionCache.ALL) {          // 종류를 가리지 않고 L1은 전부 만듭니다
            manager.registerCustomCache(cache.getName(),
                    Caffeine.newBuilder()
                            .maximumSize(LOCAL_MAX_SIZE)
                            .expireAfterWrite(cache.getTtl())  // 선언이 들고 있는 L1 TTL입니다
                            .recordStats()
                            .build());
        }
        return manager;
    }

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory factory, ObjectMapper objectMapper) {
        RedisCacheManagerBuilder builder = RedisCacheManager.builder(factory);
        for (MyCache cache : ExhibitionCache.ALL) {
            if (cache instanceof MyCache.TwoTierCache twoTier) {   // 2단만 L2 설정을 갖습니다
                Duration ttl = twoTier.getRedisTtl()
                        .plusMinutes(ThreadLocalRandom.current().nextLong(0, JITTER_MAX_MINUTES));
                builder.withCacheConfiguration(cache.getName(), /* … */);
            }
        }
        return builder.build();
    }
}
```

`CacheConfig` 클래스는 선언 목록을 순회하며 캐시 이름과 TTL을 등록하는 역할을 합니다.
선언이 TTL과 값 타입을 이미 들고 있기 때문에 이 파일은 그 값을 읽기만 하며, 그래서 캐시가 늘어나도
`CacheConfig`는 바뀌지 않습니다.

두 루프의 차이도 눈여겨보실 만합니다. L1 루프는 `getTtl()`만 쓰므로 종류를 가리지 않지만, L2 루프는
`getRedisTtl()`이 필요해서 `instanceof`로 좁힙니다. 필요한 곳에서만 좁힌다는 원칙이 코드에 그대로 드러납니다.

나중에 단층 Redis 캐시를 실제로 쓰게 된다면 이 `if`를 `switch` 패턴으로 넓히는 것이 자연스러울 것입니다.

---

## 더 나아가기

이 문서의 범위를 벗어나지만 함께 알아 두면 좋은 주제들을 정리합니다.

- **바이너리 호환성**: 허용 타입 추가는 바이너리 호환 변경이지만, 기존에 컴파일된 `switch`의 런타임 동작까지
  보장하지는 않습니다. 계층을 바꿀 때는 소비하는 모듈을 함께 다시 빌드해야 합니다.
- **`MatchException`**: 다시 컴파일하지 않은 옛 `switch`가 새로운 하위 타입을 만나면 이 예외가 발생할 수 있습니다.
- **공개 API에 적용할 때**: 외부에서 구현해야 하는 확장 지점을 `sealed`로 바꾸면 기존 사용자의 구현을 막게 되므로,
  적용 전에 외부 확장 요구가 있는지 먼저 확인해야 합니다.

---

## 마치며

이번 문서에서는 여운의 캐시 선언 계층에 `sealed` 클래스를 적용한 경험을 공유했습니다.
결과적으로 얻은 것은 세 문장으로 요약할 수 있습니다. 캐시의 종류는 셋으로 닫혀 있고, 그 아래 캐시 목록은
도메인이 자유롭게 늘릴 수 있으며, 2단 캐시에만 있는 값은 타입을 좁혀야만 읽을 수 있습니다.

| 항목                | 저희 코드에서의 의미                                             |
|-------------------|---------------------------------------------------------|
| `sealed`          | 캐시 종류가 로컬·Redis·2단 셋뿐임을 선언에 박아 둡니다                      |
| `permits`         | 같은 파일이라 생략할 수 있지만, 첫 줄에서 읽히도록 명시했습니다                    |
| `non-sealed`      | 도메인 캐시가 다른 패키지에 있어 열지 않으면 선언 자체가 불가능한, 필수 선택입니다         |
| `instanceof` 패턴   | `CacheConfig`가 L2 TTL을 읽기 전에 2단임을 증명하는 수단입니다            |
| 패턴 `switch`       | 현재는 쓰지 않으며, `getByCache`의 완전성은 sealed가 아니라 enum에서 옵니다   |
| 완전성               | 종류의 누락은 잡아 주지만, `ALL` 목록의 누락은 잡아 주지 못합니다               |
| record 패턴         | 캐시 선언이 아니라 캐시 값에 적용할 수 있습니다                             |
| 다형성과 분기           | 이름 짓기는 `MyCache`가, 저장소와 TTL 조립은 바깥에서 담당합니다              |

만들면서 가장 크게 배운 점은, 판단의 기준이 "몇 개인가"가 아니라 **"종류마다 들고 다닐 것이 다른가"**였다는
것입니다. 다르지 않으면 enum이 가장 짧고 안전한 답이고, 다르다면 그 차이를 필드의 null이 아니라 타입으로
말하게 해야 합니다.

같은 코드 안에 `CacheType`은 enum으로, `MyCache`는 닫힌 계층으로 남아 있는 이유가 여기에 있습니다.
둘 중 하나를 고른 것이 아니라, 각자 맞는 자리에 둔 것입니다. 감사합니다.
