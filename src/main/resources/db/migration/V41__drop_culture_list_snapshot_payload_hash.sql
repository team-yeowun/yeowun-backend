-- 목록 스냅샷의 변경 감지를 해시 컬럼 대신 적재 필드 직접 비교로 전환한다.
-- payload를 통째로 들고 있던 시절의 잔재였고(ADR-01), 필드가 컬럼이 된 뒤로는 비교가 곧 판정이라 저장할 이유가 없다.
-- 상세 스냅샷에는 애초에 해시 컬럼이 없다(대상당 1회 기록층 — 재적재 트리거가 없다).
alter table culture_list_snapshot drop column payload_hash;
