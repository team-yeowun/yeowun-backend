-- 원장 멱등 범위를 실행 단위 → 전시 단위로 되돌린다(V1 결).
--
-- 배경: 앵커 UNIQUE가 (job_name, batch_date, vendor_key)라 날짜가 바뀌면 새 execution이 생기고,
-- 원장 UNIQUE가 job_execution_id였던 탓에 새 실행에는 원장이 없어 보강 스킵이 매번 빗나갔다.
-- 결과적으로 같은 전시의 상세·장르·구글 호출이 매일 반복됐다(유료 API 2종 포함).
--
-- 변경: 원장 UNIQUE를 vendor_key로 옮긴다 — 전시당 1행이 되어 재수집이 no-op가 된다.
-- job_execution_id 컬럼은 "어느 실행이 기록했나"의 감사 정보로 남긴다(첫 기록자).

-- 목록 원장
drop index uk_ingestion_list_snapshot_job on ingestion_culture_list_snapshot;
create unique index uk_ingestion_list_snapshot_vendor on ingestion_culture_list_snapshot (vendor_key);
create index idx_ingestion_list_snapshot_job on ingestion_culture_list_snapshot (job_execution_id);

-- 상세 원장
drop index uk_ingestion_detail_snapshot_job on ingestion_culture_detail_snapshot;
create unique index uk_ingestion_detail_snapshot_vendor on ingestion_culture_detail_snapshot (vendor_key);
create index idx_ingestion_detail_snapshot_job on ingestion_culture_detail_snapshot (job_execution_id);

-- 장르 원장
drop index uk_ingestion_genre_snapshot_job on ingestion_genre_snapshot;
create unique index uk_ingestion_genre_snapshot_vendor on ingestion_genre_snapshot (vendor_key);
create index idx_ingestion_genre_snapshot_job on ingestion_genre_snapshot (job_execution_id);

-- 구글 원장
drop index uk_ingestion_google_snapshot_job on ingestion_google_place_snapshot;
create unique index uk_ingestion_google_snapshot_vendor on ingestion_google_place_snapshot (vendor_key);
create index idx_ingestion_google_snapshot_job on ingestion_google_place_snapshot (job_execution_id);
