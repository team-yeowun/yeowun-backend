# 백엔드 아키텍처 컨벤션 (AI 컨텍스트 파일)

> 통치용 컨텍스트 파일(README 아님). 사람이 소유·수정한다.
> 매 줄은 "지우면 AI가 틀리는가?"를 통과해야 한다.

## 핵심 (위반 잦음 — 이것만은 반드시)
- **IMPORTANT: 상태 변경은 Entity 메서드 안에서만 한다.** Facade는 load·조율·save만. 비즈니스 로직을 Facade에 쌓지 마라.
- 여러 도메인 Repo 조합 호출 = Facade만. 도메인이 다른 도메인 Repo 직접 호출 ❌
- 다른 애그리거트는 ID·값만 전달. Entity 통째 ❌, 경계 넘는 @ManyToOne ❌(ID로)
- 성공 전부 200 · PATCH 미사용은 **의도된 선택**. 201/PATCH 끼우지 마라.

## 스택 / 검증
- 런타임: Java `21` · Spring Boot `4.1.0` · 빌드 `Gradle`
- 저장소: MySQL `8.4` · JPA(Hibernate) · QueryDSL
- 패키지 루트: `modi.backend`
- 생성·수정 후 반드시 실행해 검증:
  - 빌드 `./gradlew build`
  - 테스트 `./gradlew test` (Testcontainers-MySQL 사용 → **Docker 데몬 필요**)
  - 정적분석(있으면) `./gradlew check` (현재 별도 정적분석 플러그인 미설정 — test까지 수행)
- 스프링 빈 주입: `@RequiredArgsConstructor` + `private final`. (생성자에서 입력을 가공하면 명시적 생성자 유지)

## 의존
`Interface(Controller) → Application(Facade) → Domain(Entity) ← Infrastructure`
- 의존은 항상 Domain 방향. Domain은 Spring/JPA/HTTP 모름.
- Infra가 Domain 포트(Repository I/F) 구현 = DIP.

## 불변 흐름
- 상태변경: `repo.find() → entity.행위() → repo.save()` (Repo·Entity메서드는 택일 아님, 항상 이 순서)
- 변환: `Request →[Controller] Criteria → Facade → Result →[Controller] Response`
- Facade는 Request/Response DTO 모름(Result까지만).

## 계층 책임 / 금지
| 계층 | 한다 | ❌ |
|---|---|---|
| Interface | HTTP 입출력, Request↔Criteria·Result↔Response 변환, 인증 진입 | 비즈니스 로직 · Entity 직접 반환 · 도메인 규칙 판단 |
| Application(Facade/Service) | 유스케이스 조율, 여러 도메인 Repo 조합 호출, 트랜잭션 경계, DTO 변환 | Entity 상태 직접 변경 · 도메인 규칙 판단 · Request/Response 취급 · 순환참조 |
| Domain | Entity=상태+단일 애그리거트 규칙+행위 / DomainService=여러 애그리거트 규칙 | 다른 도메인 Repo 직접 호출 · 인프라·HTTP 의존(getStatus만 예외) |
| Infrastructure | Repo 구현, JPA/QueryDSL 영속화 | 비즈니스 로직 |

- Facade에 Repo 5개+ 주입 = 비대 신호 → **책임별 `XxxService`로 분리하고 Facade는 그것들을 DI해 조율**한다.
- **IMPORTANT: interfaces는 Facade만 호출한다.** Service는 Facade 뒤에 숨는다(컨트롤러가 Service 직주입 ❌).
- Service 하나 = 응집된 유스케이스 묶음(예: 조회 / 개인 등록·삭제). Service→Service 호출 ❌ — 조합은 Facade가 한다.
- 여러 애그리거트 걸친 규칙 = `domain/{d}/XxxDomainService`에 둔다. Facade는 호출만, 규칙 판단 ❌.

## 도메인 간 참조
- 다른 애그리거트엔 ID·값(VO/원시값)만 전달. `order.place(user)` ❌ → `order.place(userId, userGrade)` ✅
- 경계 넘는 참조 = @ManyToOne 대신 ID. (객체그래프 항해 = 결합 + LazyInitializationException 원인)

## @Entity 겸용 (도메인=DAO)
- 변경 후 `repository.save()` 명시 호출. (dirty checking으로 flush되더라도 저장 지점이 코드에 보이게)
- dirty checking에만 의존해 save 생략 ❌
- readOnly 메서드 내 Entity 변경 ❌

## 예외
```
CoreException → ErrorCode(I/F: code·message·getStatus)
                ├ ErrorType(enum)    공통  support/error/
                └ XxxErrorCode(enum) 도메인별 domain/{domain}/
```
- HTTP 매핑 `ErrorCode.getStatus()`. (HTTP 누수는 의도된 실용 선택)
- 메시지는 ErrorCode enum 한 곳에만. 컨트롤러/Facade는 `throw new CoreException(XxxErrorCode)` — ad-hoc 응답 ❌.
- 전역 매핑: `support/error/GlobalExceptionHandler`(@RestControllerAdvice). 미처리 예외는 500으로 덮고 상세는 로그만.

## 응답
```
ApiResponse.success(data)
ApiResponse.fail(code, message)
ApiResponse.failValidation(code, message, fieldErrors)
```

## API
- Prefix: 고객 `/api/v1` · Admin `/api-admin/v1`
- 리소스: 복수형·소문자·케밥 (`/api/v1/products`)
- 메서드: GET조회 POST생성 PUT수정 DELETE삭제. PATCH ❌
- 상태코드: 성공 전부 200(201 ❌), 에러 `getStatus()`. ※의도된 단순화 — 201/PATCH 끼우지 마라
- 페이지: Offset `page=0&size=20`. Page/Pageable은 Application까지 허용
- Controller: 고객 `{Domain}V1Controller` / Admin `Admin{Domain}V1Controller` (Facade 공유 가능)
- 인증 유저: `@Authentication LoginUser user` 파라미터로 주입(HandlerMethodArgumentResolver가 Bearer access 검증). 컨트롤러에서 헤더 직접 파싱 ❌. (Filter ❌ — 디스패처 밖이라 전역 핸들러 우회)
- Swagger: Swagger 어노테이션→`{Domain}V1ApiSpec`, MVC 어노테이션→Controller. Controller가 implements+@Override. 필수 @Tag/@Operation/@Parameter, @Schema는 모호 필드만. 주입 파라미터(`@Authentication`)·서블릿 타입은 `@Parameter(hidden=true)`.

- **전시 application은 축 패키지**: 루트 = 정문(`ExhibitionFacade`)+공용 입출력(`ExhibitionCriteria`/`Result`)만, 나머지는 `list/`(목록·배너·조건조립·항목조립) `detail/` `custom/` `contract/`(계약+구현) `seed/`. ingestion 슬라이스와 같은 결(축 패키지 + 루트 조합자 하나)이다.

## Value Object
- 생성 조건: 도메인 불변식 있음 OR 원시값 오용 타입 차단 (단일 도메인 OK). record 권장.
- 영속화 안 함: VO는 도메인 로직(생성·검증·계산) 전용. Entity는 원시값으로 저장하고 필요 시 VO로 감싸 사용(`Money.of(amount)`). @Embeddable ❌.

## 검증 위치
| 종류 | 위치 |
|---|---|
| 형식 (@NotNull/@Size/포맷) | Request DTO, Bean Validation |
| 도메인 불변식 | Entity/VO 생성·행위 시점 |

같은 규칙 양쪽 중복 ❌. 불변식을 Controller에서 판단 ❌.

## Repository 3-클래스
- `domain/{d}/XxxRepository` — 순수 I/F (Spring 무의존)
- `infra/{d}/XxxJpaRepository` — Spring Data JPA
- `infra/{d}/XxxRepositoryImpl` — @Repository 어댑터. JPAQueryFactory 주입해 QueryDSL 직접 작성.
- soft-delete: 조회는 살아있는 행만 — 파생쿼리에 `...AndDeletedAtIsNull`(포트 메서드명은 그대로, Impl만 필터 호출).
- **전시 예외(애그리거트 루트)**: 전시·전시장 부속(상세·장르·작가조인·영업시간)은 개별 리포 ❌ — 쓰기는 `ExhibitionRepository`/`ExhibitionPlaceRepository`의 `applyXxx`(루트 행 미변경 부분 갱신) 경유, 서빙 목록/탐색 조회는 `ExhibitionQueryRepository`. 부속 JpaRepo는 Impl 내부 구현 세부라 application 주입 ❌. 기록성 테이블(벤더 Snapshot·History)은 포트 없이 JpaRepository 직사용(단 `ExternalApiCallLogRepository`는 감사 포트라 존치 — **코어 공용 audit 수직** `domain/audit`+`infra/audit`, REQUIRES_NEW+예외 삼킴은 코어 `application/audit/ExternalApiCallLogRecorder` 소유. `source` 컬럼(INGESTION/RECORD/REMIND)이 기능 축 귀속).
- **전시 수집 = `modi.backend.ingestion` 수직 슬라이스**(자체 {domain,application,infra,interfaces,config} 4계층 미러 — ADR-12). 수집·승격·아웃박스·벤더 스냅샷·릴레이·스케줄러가 전부 이 안이다("싱크 건드릴 땐 이 폴더만"). 내부 구조는 기존 스타일 강제 없음(사용자 결정 — catalog 스타일로 "교정"하지 마라).
- **ingestion→코어 접근은 `application/exhibition/contract/` 인터페이스만**(구현체도 같은 폴더에 있지만 **구현 클래스명 참조 ❌** — 주입은 인터페이스 타입으로)(ExhibitionRegistrar=승격 등록(Registered=전시 id만)·PlaceRegistrar=전시장 resolve-or-create(+created 판정)·PlaceHoursGateway=영업시간 정준 반영(applyHours 하나 — 선별·가드·실패마킹은 재검증 폐기로 삭제)) — 코어 리포 직주입 ❌. 예외: 공용 감사 `ExternalApiCallLogRecorder`(코어 application/audit)는 support성 공용부라 직접 주입 허용. 계약 어휘는 코어 소유(원시값·코어 enum — ingestion record가 경계 넘기 ❌). 코어→ingestion 참조 ❌(javadoc @link 포함).
- **수집 = 이벤트 아웃박스**(`docs/전시수집_파이프라인_설계문서/`의 PR 문서 4종이 진실): 아웃박스 레코드는 발행자의 사실(이벤트 4종 — DRAFT_STAGED·PLACE_STAGED·DETAIL_FETCHED·DRAFT_READY)이지 커맨드 ❌. **발행자는 `ExhibitionProgressService` 하나**(반영 tx 원자, `OutboxPublisher` 컴포넌트 경유), **이벤트→다음 스텝 매핑은 `ExhibitionIngestionOrchestrator` 한 파일만**(try/catch ❌ — 예외 번역은 outbox consume). 진입점도 `syncCatalog` 하나(발견=목록 스테이징, 실행은 전부 릴레이 소비 — 폴링 12h+커밋 직후 적재 알림). 스텝 = [판정(tx)→외부 호출(tx 밖, 직후 콜 감사)→반영(tx+이벤트)] 3박자. **원장 합류 규칙**: 스냅샷(목록·상세·장르)은 `SnapshotLedger`(REQUIRED)가 반영 tx에서 기록 — best-effort ❌, 마커가 있으면 원장이 반드시 있다(어셈블 근거). 승격 = DRAFT_READY 멱등 소비 → `ExhibitionAssembler`(원장 3종→등록 입력) → Registrar. 장르는 **진행 행당 개별 분류**(classifyAll 배치 재도입 ❌), Gemini→OpenAI 폴백은 분류기 체인 내부. **재시도 정책 단일**: 총 3회(최초1+재시도2) 소진 시 FAILED_PERMANENT + progress FAILED 가시화(장르 무기한 특례 폐지) — 회생은 관리자 대시보드 수동 재시도만(`/api-admin/v1/ingestion`, admin.html 수집 탭). SUCCEEDED는 주간(일 03시) 7일 경과분 소량 배치 삭제(FAILED_PERMANENT 보존 — 100만건 실험 근거, "DELETE 한 방" ❌). **영업시간 재검증 폐기**: 조회는 PLACE_STAGED 소비(신규 전시장) 1회뿐 — 스윕·PLACE_HOURS_STALE 재도입 ❌, 갱신 필요 시 일회성 배치로. 보상 설계 ❌ — 전진 복구만. **슬라이스 내 의존 규칙**: 서비스→서비스 ❌(발행·원장·어셈블은 컴포넌트로), 조합자(오케스트레이터·파사드)→조합자 ❌.
- **스냅샷 = 각 스텝의 데이터 원장**(CultureListSnapshot·CultureDetailSnapshot·GenreSnapshot·GooglePlaceSnapshot — ADR-13 확장): 벤더 응답은 구조 필드 verbatim 적재(payload 문자열 ❌, 타입 정제·평문 추출은 도메인/어셈블 몫), GenreSnapshot만 정제 결과(키워드+공급자+모델). 진행 상태(`exhibition_progress`, 구 draft — 슬림)는 마커만 갖고 값은 전부 원장에 있다. 깊은 중첩(구글 영업시간)만 구조 보존 JSON 컬럼.
- **infra 외부 어댑터 경로 규칙 = `<슬라이스/도메인 스코프>/<기관>`**(예: ingestion/infra/{culture,google,ai,mock,failover} + 소유권 패키지 {snapshot,audit,progress,outbox}, infra/ai/claude=record·remind 공유) — infra/genre·infra/place 같은 스코프 없는 기능 패키지 재생성 ❌. ingestion application은 축 패키지(culture·genre·place·progress·outbox·audit·admin + 루트 `ExhibitionIngestionOrchestrator` 유일). 축 서비스 5개 + 컴포넌트(OutboxPublisher·SnapshotLedger·ExhibitionAssembler·Recorder 2종) + 관리자 파사드(IngestionAdminFacade).

## Entity
- 생성: 정적 팩토리 `Xxx.create(...)`
- `@NoArgsConstructor(PROTECTED)`, Setter ❌

## 테스트
| 대상 | 방식 |
|---|---|
| Domain (Entity/VO/DomainService) | 순수 단위 (컨텍스트 X) |
| Controller | @WebMvcTest |
| Repository | @DataJpaTest |
| Facade·Service 유스케이스 | @SpringBootTest |

given-when-then. 네이밍 `메서드_조건_기대` 또는 한글 @DisplayName. 단위로 충분한데 @SpringBootTest ❌.
외부 I/O(OAuth HTTP 등)가 끼는 Facade는 의존 모킹한 Mockito 단위로 대체 가능(실연동은 통합 플로우 테스트로).

## 네이밍
| 용도 | 위치 | 접미사 |
|---|---|---|
| 요청/응답 DTO | interfaces | `{Domain}Dto`(외곽) 안에 중첩 record `XxxRequest`/`XxxResponse` |
| 유스케이스 입력/출력 | application | `{Domain}Criteria`/`{Domain}Result`(외곽) 안에 중첩 record(용도명) |
| 유스케이스 진입점(조율) | application | `XxxFacade` |
| 유스케이스 책임 단위 | application | `XxxService` |
| 여러 애그리거트 규칙 | domain | `XxxDomainService` |
| 도메인 모델(=DAO) / 값 객체 | domain | `Xxx`(Entity) / `Xxx`(record) |
| 포트 / Spring Data / 어댑터 | domain·infra | `XxxRepository` / `XxxJpaRepository` / `XxxRepositoryImpl` |
| 컨트롤러 / Swagger 스펙 | interfaces | `XxxV1Controller`·`AdminXxxV1Controller` / `XxxV1ApiSpec` |

- DTO·Criteria·Result는 **파일 1개당 1 record 금지 → 도메인별 외곽 클래스에 중첩 record로 묶는다**(파일 수 절감). 외곽=`{도메인}{역할}`(Dto/Criteria/Result), 중첩=용도명. 예: `AuthDto.LoginRequest`·`AuthDto.TokenResponse`, `AuthCriteria.Login`, `AuthResult.Link`, `UserCriteria.ProfileUpdate`.
