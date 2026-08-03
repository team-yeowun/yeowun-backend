-- 지역·무료를 exhibitions로 내린다 (STEP 5 — 07장 작업 3 / 08장 설계 1의 전제조건)
--
-- 없애는 술어 두 개:
--   지역  exhibition_place_id IN (SELECT id FROM exhibition_place WHERE region IN (...))  → region IN (...)
--   무료  id IN (SELECT exhibition_id FROM exhibition_detail
--                WHERE lower(price) LIKE '%무료%' OR price='0원')                          → is_free = 1
--
-- 두 술어 모두 "다른 테이블 서브쿼리"였고, 무료 쪽은 그 위에 선행 와일드카드 LIKE라 인덱스가 원천 불가였다.
-- 500,174행 실측(버퍼풀 128M, 3회):
--   무료 count   2,223ms → 1,016ms (-54%)
--   지역 count   1,422ms →   991ms (-30%)
--   지역+장르     1,492ms → 1,089ms (-27%)
--   무필터 count 1,043ms (변화 없음 — 여전히 65.5%를 통과하는 풀 스캔이다)
-- 즉 "필터가 무필터보다 비싼" 페널티가 사라진 것이지 count 자체가 빨라진 것이 아니다.
--
-- ── 컬럼 두 개의 성격이 다르다 ──────────────────────────────────────────────
-- region  : 전시장의 그 시점 스냅샷. 전시장 region이 나중에 바뀌어도 갱신하지 않는다(사용자 결정).
--           그래서 표시용 region도 이 복제본에서 읽는다 — 필터와 표시가 어긋나지 않게.
-- is_free : exhibition_detail.price를 도메인 규칙(Exhibition.isFreePrice)으로 판정해 굳힌 값.
--           price 원본은 그대로 남는다 — 규칙이 바뀌면 아래 UPDATE를 다시 돌려 재계산한다.
--
-- ── NULL 허용 판단 ────────────────────────────────────────────────────────
-- region  NULL 허용. exhibition_place.region이 nullable이다(CUSTOM 등록은 지역 미지정 가능,
--         Venue에도 region이 없을 수 있다). NOT NULL로 굳히려면 enum에 UNKNOWN을 새로 만들어야 하는데
--         그러면 응답의 region이 null → "UNKNOWN"으로 바뀌어 계약이 깨진다.
--         판정 결과는 그대로다: 옛 서브쿼리도 region이 NULL인 전시장을 IN에서 걸러냈고,
--         새 술어 region IN (...)도 NULL 행을 걸러낸다.
-- is_free NOT NULL DEFAULT false. "가격 미상 = 무료 아님"이 기존 규칙과 같다
--         (Exhibition.isFree(null) == false, 옛 서브쿼리도 detail 행이 없으면 매칭 안 됨).
--         상세가 아직 안 온 전시는 자연히 false로 시작하고, 상세가 도착하면 그때 굳는다.
--
-- ── 백필 소요(실측, 500,174행 / 버퍼풀 128M) ────────────────────────────────
--   ALTER (컬럼 2개 추가)  0.08s   ← MySQL 8 INSTANT ADD COLUMN
--   region  UPDATE        10.8s   (500,174행 전부)
--   is_free UPDATE         5.0s   (364,344행 갱신)
--   합계                  약 16s
-- 100만 행이면 약 32초로 본다. 부팅 시 Flyway에 얹히므로 헬스체크 대기시간을 넉넉히 잡을 것.
-- 배치로 쪼개지 않았다 — 단일 UPDATE 16초가 락 유지 시간으로 허용 범위이고(적재는 12시간 폴링),
-- 쪼개면 중단 시 반쯤 채워진 상태가 남아 멱등성이 오히려 나빠진다.
--
-- ── 되돌리기 ──────────────────────────────────────────────────────────────
--   create index idx_exhibition_place_region on exhibition_place (region, id);
--   alter table exhibitions drop column is_free, drop column region;
--   (실측 되돌리기 소요: 인덱스 재생성 1.5s + 컬럼 드롭 ~4s)

alter table exhibitions
    add column region  varchar(20) null comment '전시장 지역 스냅샷(적재 시점 복제 — 갱신하지 않는다)',
    add column is_free boolean not null default false comment '무료 판정 스냅샷(Exhibition.isFreePrice(price) 결과)';

-- 1) 지역 복제 — 전시장에서 그대로 가져온다.
update exhibitions e
   join exhibition_place p on p.id = e.exhibition_place_id
   set e.region = p.region;

-- 2) 무료 판정 — Exhibition.isFreePrice(String)와 같은 규칙이다. 둘이 어긋나면
--    기존 행(SQL 백필)과 신규 행(도메인 코드)이 다른 규칙을 타므로 반드시 같이 고쳐야 한다.
--
--    규칙: 0이 아닌 "숫자+원" 금액이 하나라도 있으면 유료. 그렇지 않고
--          "무료"가 들어 있거나 표기된 숫자가 전부 0이면 무료.
--    옛 규칙(LIKE '%무료%' OR price='0원')은 "성인 2,000원 / 노인 및 유아 무료" 류를
--    전체 무료로 잡았다(원본 313건 중 13건, 오탐률 4.2%). 그 13건이 무료 목록에서 빠진다 — 의도된 변화다.
update exhibitions e
   join exhibition_detail d on d.exhibition_id = e.id
   set e.is_free = 1
 where d.price is not null
   and trim(d.price) <> ''
   and not regexp_like(d.price, '[1-9][0-9,]*[[:space:]]*원')
   and (d.price like '%무료%' or regexp_like(regexp_replace(d.price, '[^0-9]', ''), '^0+$'));

-- 3) exhibition_place(region, id) 제거 — 소비자가 사라졌다.
--    V48이 이 인덱스를 만든 유일한 이유는 위에서 없앤 지역 서브쿼리였다. 저장소 전수 조사 결과
--    exhibition_place.region을 술어로 쓰는 조회는 그 서브쿼리 하나뿐이었고(나머지는 쓰기 경로의
--    resolveOrCreate/enrichIdentity), 비정규화 후 실행계획에서 exhibition_place 접근이 사라졌다.
--    STEP 4 실측에서 이 인덱스는 1경로를 살리고 4경로를 악화시켰는데(검증자: 살린 경로의 깊이 자체가
--    증폭 아티팩트), 이제 살릴 경로마저 없다. 17.5MB + 전시장 INSERT마다 B-tree 갱신을 돌려받는다.
drop index idx_exhibition_place_region on exhibition_place;
