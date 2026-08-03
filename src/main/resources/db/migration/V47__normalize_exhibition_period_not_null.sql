-- 전시 기간(start_date·end_date)의 NULL을 센티널로 정규화하고 NOT NULL로 굳힌다.
--
-- 왜 하는가
--   진행 중 판정 술어가 지금은 이 모양이다:
--     (start_date IS NULL OR start_date <= ?) AND (end_date IS NULL OR end_date >= ?)
--   OR + IS NULL이 섞여 있으면 옵티마이저가 범위 스캔을 세우지 못한다.
--   NULL을 "과거 무한 / 미래 무한"을 뜻하는 값으로 바꾸면 술어가
--     start_date <= ? AND end_date >= ?
--   단순 범위가 되어 인덱스(다음 단계)가 살 수 있다.
--   이 마이그레이션 단독으로는 빨라지지 않는다 — 인덱스의 선행 조건이다.
--
-- 센티널 값 선택 근거
--   start_date 미상 → '1000-01-01' (= 이미 시작한 것으로 취급)
--   end_date   미상 → '9999-12-31' (= 아직 진행 중인 것으로 취급)
--
--   설계 문서는 예시로 '0001-01-01'을 들었으나 '1000-01-01'을 택했다:
--     · MySQL이 문서로 보장하는 DATE 지원 범위가 '1000-01-01' ~ '9999-12-31'이다.
--       그 아래 값도 저장·비교는 되지만(MySQL 8.4.10에서 확인) 지원 범위 밖이고,
--       DATE 산술(INTERVAL 뺄셈)이 '0000-00-00' 쪽으로 넘어가면 NO_ZERO_DATE(현재 sql_mode에 켜져 있음)에 걸린다.
--       loadtest/seed/amplify.sh가 실제로 `start_date + INTERVAL -15 DAY`를 쓴다.
--     · 두 값 모두 java.time.LocalDate 왕복이 무손실이다(LocalDate의 표현 범위는 ±999999999년).
--     · 실제 전시 데이터가 이 두 값을 가질 일이 없어 "미상"의 표식으로 안전하다.
--
--   주의: 이 인코딩은 "모름"과 "아주 옛날/아주 먼 미래"를 값으로 구분하지 못한다.
--   그래서 애플리케이션은 Exhibition 엔티티에서 센티널을 다시 null로 되돌려 밖으로 내보낸다
--   (Exhibition.getStartDate()/getEndDate()). 정렬·커서 경계만 저장값 그대로를 쓴다.
--
-- 대상 범위
--   CATALOG·CUSTOM을 가리지 않고 exhibitions 전 행이다.
--   개인(CUSTOM) 전시도 같은 테이블·같은 컬럼을 쓰므로 제약을 반쪽만 걸 수 없다.
--
-- 실행 비용
--   UPDATE는 NULL인 행만 건드린다(측정용 100만 볼륨 데이터셋에는 NULL이 0건 — 0행 갱신).
--   ALTER는 nullability 변경이라 InnoDB가 테이블을 재구축한다.
--   ALGORITHM=INPLACE / LOCK=NONE을 명시해 동시 DML을 막지 않는 경로로만 수행되게 하고,
--   그 경로가 불가능하면 조용히 COPY로 떨어지는 대신 즉시 실패하게 한다.
--
-- 되돌리기
--   ALTER TABLE exhibitions
--       MODIFY COLUMN start_date DATE NULL,
--       MODIFY COLUMN end_date   DATE NULL,
--       ALGORITHM=INPLACE, LOCK=NONE;
--   UPDATE exhibitions SET start_date = NULL WHERE start_date = '1000-01-01';
--   UPDATE exhibitions SET end_date   = NULL WHERE end_date   = '9999-12-31';
--   (되돌리면 센티널이었던 행과 원래 그 날짜였던 행이 구분되지 않는다 — 후자는 실제로 존재하지 않는다.)

UPDATE exhibitions SET start_date = '1000-01-01' WHERE start_date IS NULL;
UPDATE exhibitions SET end_date   = '9999-12-31' WHERE end_date   IS NULL;

ALTER TABLE exhibitions
    MODIFY COLUMN start_date DATE NOT NULL,
    MODIFY COLUMN end_date   DATE NOT NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
