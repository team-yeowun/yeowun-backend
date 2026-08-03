-- 전시 목록 정렬 인덱스 — "필터를 좁히는 인덱스"가 아니라 "ORDER BY를 대신하는 인덱스"다.
--
-- ── 왜 필터형 인덱스가 답이 아닌가 (1,000,035행 실측, 2026-07-30) ────────────────
--   type='CATALOG'                              1,000,035 / 1,000,035 =  100.0%  ← 못 거른다
--   deleted_at IS NULL                          1,000,035 / 1,000,035 =  100.0%  ← 못 거른다
--   start_date <= 오늘 AND end_date >= 오늘        654,672 / 1,000,035 =   65.5%  ← 못 거른다
-- 진행 중 판정은 세 건 중 두 건을 통과시킨다. 어떤 인덱스로 이 술어를 좁혀도 66만 행이 남는다.
-- 실제로 V5의 idx_exhibitions_dates(start_date, end_date) 범위 스캔을 FORCE로 강제해 보면
-- 풀 스캔 1,598ms보다 2.2배 느린 3,557ms였다(STEP 3 측정, 3회 재현). 옵티마이저가 그걸 안 고른 게 옳았다.
--
-- ── 남은 비용은 필터가 아니라 정렬이다 ─────────────────────────────────────────
-- 목록은 66만 행을 정렬해 21건만 꺼낸다(Using filesort). 그래서 인덱스의 목표는 정렬 제거다.
-- InnoDB 보조 인덱스는 PK가 뒤에 붙는다 → KEY(start_date)는 물리적으로 (start_date, id)이고,
-- 이건 ORDER BY start_date DESC, id DESC 와 정확히 같은 순서다. 역방향으로 걸어가며
-- 조건 맞는 21건을 채우고 멈출 수 있다(읽는 행 66만 → 수십).
--
-- 이미 증거가 테이블 안에 있었다: 인기순은 idx_exhibitions_view_count(our_view_count)가
-- 물리적으로 (our_view_count, id)라서 예전부터 33행만 읽고 1.4ms에 끝났다.
-- 최신순·종료순만 그 모양의 인덱스가 없어서 1,900ms였다. 없던 걸 만드는 게 아니라, 빠진 두 축을 맞춘다.
--
-- ── 왜 idx_exhibitions_dates 로는 안 되는가 (컬럼 순서의 핵심) ──────────────────
-- (start_date, end_date) + PK = (start_date, end_date, id). start_date가 같은 행이
-- 평균 938개(1,000,035행 / 고유 start_date 1,066개)인데, 그 안의 순서는 id가 아니라 end_date다.
-- 즉 ORDER BY start_date DESC, id DESC 를 만족시키지 못해 filesort가 남는다.
-- 정렬 타이브레이커 id 바로 앞에 다른 컬럼이 끼면 인덱스는 정렬을 대신할 수 없다.
--
-- ── 왜 선행에 type을 두지 않는가 (문서 제안 (type, end_date, id) 기각) ──────────
-- type은 고유값이 1개(전 행 CATALOG)라 거르는 게 0이다. 게다가 varchar(20) utf8mb4라
-- 키 하나에 최대 80바이트가 붙는다. 실측: (type,start_date,id) 27.6MB vs (start_date,id) 18.5MB (+49%),
-- 생성 2.9s vs 1.7s. 속도는 FORCE 대조에서 0.089ms vs 0.070ms로 구분되지 않았다.
-- 아무것도 못 거르는 컬럼에 49%를 더 내는 셈이라 뺐다.
--
-- ── 실측 (EXPLAIN ANALYZE, 100만 행, 버퍼풀 128M 웜, 2회 중 안정값) ─────────────
--   S1 최신순 무필터        1,910ms → 0.27ms      D100 커서 100p     1,923ms → 1.05ms
--   S2 종료순 무필터        1,920ms → 1.08ms      H1 곧끝남×최신      1,852ms → 0.17ms
--   H3 이번달×최신          (동류)  → 0.08ms      F1 서울×최신        2,749ms → 0.38ms
--   K1 검색 고빈도(미술)    3,075ms → 1.45ms      X8 무료×종료        2,715ms → 3.33ms
--
-- ── 되돌리기 ────────────────────────────────────────────────────────────────
--   DROP INDEX idx_exhibitions_start_id ON exhibitions;
--   DROP INDEX idx_exhibitions_end_id   ON exhibitions;
--   DROP INDEX idx_exhibition_place_region ON exhibition_place;
--   CREATE INDEX idx_exhibitions_dates ON exhibitions (start_date, end_date);   -- 아래 3)의 복구
-- 생성 소요(100만 행/73만 행 실측): 1.7s · 1.6s · 1.5s. 부팅 시 마이그레이션에 얹힌다.

-- 1) 최신순(기본 정렬) — ORDER BY start_date DESC, id DESC
--    id를 명시해 "이 인덱스는 정렬용"임을 드러낸다(InnoDB가 어차피 붙이므로 크기는 KEY(start_date)와 같다).
create index idx_exhibitions_start_id on exhibitions (start_date, id);

-- 2) 종료 임박순 — ORDER BY end_date ASC, id ASC
--    end_date >= 오늘 이 범위 시작점이 되고, 그 지점부터 정방향으로 걸으면 곧 21건이 찬다
--    (end_date >= 오늘인 697,132행 중 65.5%가 진행 중이라 통과율이 높다).
create index idx_exhibitions_end_id on exhibitions (end_date, id);

-- 3) 이제 idx_exhibitions_dates(start_date, end_date)는 지배당한다 — 지운다.
--    · start_date 범위 → 1)이 더 작게(18.5MB vs 21.5MB) 하고, 덤으로 정렬까지 한다
--    · end_date  범위 → 2)가 한다
--    · 유일한 고유 능력이던 "두 날짜를 인덱스 안에서 필터"는 위에 적었듯 풀 스캔보다 2.2배 느렸다
--    · 측정한 30여 개 실행계획 어디에서도 선택되지 않았고, 앱 쿼리 중 이 모양을 쓰는 것이 없다
--      (북마크 종료임박 조회는 id IN 이라 PK를 탄다)
--    선행 컬럼이 같은 인덱스를 둘 두면 INSERT마다 start_date 키 B-tree를 두 번 갱신한다.
drop index idx_exhibitions_dates on exhibitions;

-- 4) 지역 필터 — exhibition_place에는 region 인덱스가 아예 없었다(전 컬럼 무인덱스).
--    이건 정렬용이 아니라 "최악의 경우를 막는" 대안 플랜용이다.
--
--    실측 지역 분포(전시 기준): SEOUL 36.7% · GYEONGGI 14.1% · … · SEJONG/GWANGJU 각 0.32%.
--    선택도만 보면 낮지만(16개 값, 최빈 37%), 정렬 인덱스 스캔의 비용은 "전체 비율"이 아니라
--    "정렬 순서 앞쪽에 얼마나 몰려 있느냐"로 정해진다. 실측한 스캔 깊이(21건 채우는 데 읽는 행 수):
--        SEOUL  (36.7%)        33행      ← 얕다
--        GWANGJU( 0.32%)      414행      ← 10배 희소한데 오히려 얕다
--        JEJU   ( 2.88%)   54,838행      ← 132배 깊다
--    즉 희소도가 아니라 분포 위치가 비용을 정한다. JEJU처럼 뒤쪽에 몰린 값은 정렬 스캔이 2,032ms까지 간다.
--    region 인덱스가 있으면 옵티마이저가 "전시장에서 출발해 조인하고 정렬"하는 대안을 갖게 되고,
--    비용 기준으로 지역마다 알아서 고른다: JEJU 2,032ms → 252ms, SEOUL은 그대로 정렬 스캔(0.38ms).
--    대가: 대안 플랜은 LIMIT 조기 종료를 포기하므로 얕은 지역은 느려진다(GWANGJU 7→25ms,
--    다중선택 IN(JEJU,GWANGJU) 8→156ms, 지역 count 2,755→3,452ms). 전부 절대값이 작거나
--    이미 느린 경로라, 최악 2초를 0.25초로 묶는 대가로 받았다.
--    (STEP 5가 region을 exhibitions에 비정규화하면 이 인덱스는 역할을 잃는다 — 그때 재검토할 것.)
create index idx_exhibition_place_region on exhibition_place (region, id);

-- ── 기각한 후보 (근거를 남긴다) ────────────────────────────────────────────────
-- · exhibitions(type, end_date, id) / (type, start_date, id)  … 문서 제안.
--   선행 type의 선택도 0 + varchar(20)라 크기 +49%. 속도 이득 없음(위 실측). → 선행 type 제거해 채택.
-- · exhibition_detail(exhibition_id, price)                    … 문서 제안.
--   46.7MB(채택한 두 날짜 인덱스 합의 1.3배)인데 무료 필터 목록 6.0ms→3.3ms(노이즈 수준),
--   무료 count 4,218ms→4,694ms(개선 없음). price가 varchar(500)이라 비용만 크다.
--   무료 판정의 진짜 해법은 STEP 5의 is_free 컬럼이다. → 만들지 않는다.
-- · exhibitions(type, owner_id) 삭제                            … 새 인덱스와 겹치지 않고
--   관리자 타입별 집계의 유일한 후보라 그대로 둔다(이번 STEP의 대상이 아니다).
