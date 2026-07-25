-- 전시 아웃박스 타입 어휘를 커맨드("다음에 할 일")에서 이벤트("내가 한 일")로 재명명한다(ingestion 재설계 §1).
-- 발행 서비스는 자기 사실만 원자 기록하고, "그 다음이 무엇인가"는 ExhibitionIngestionOrchestrator 매핑 한 곳만 안다.
-- 흐름은 1:1 불변이라 값 치환만 하면 된다. 컬럼명(message_type)·UK·길이(30)는 메커니즘의 어휘라 그대로 둔다.

update exhibition_outbox set message_type = 'DRAFT_STAGED' where message_type = 'FETCH_DETAIL';
update exhibition_outbox set message_type = 'DETAIL_FETCHED' where message_type = 'CLASSIFY_GENRE';
update exhibition_outbox set message_type = 'DRAFT_READY' where message_type = 'EXHIBITION_READY';
update exhibition_outbox set message_type = 'PLACE_HOURS_STALE' where message_type = 'REFRESH_PLACE_HOURS';

-- FETCH_PLACE_HOURS(영업시간 최초 조회)는 정의만 있고 발행 지점이 없던 죽은 어휘라 이관 없이 정리한다.
-- 최초 조회는 이벤트가 아니라 스케줄 선별 루프(enrichPlaceHours)가 담당한다. 행이 있을 수 없지만 방어적으로 지운다.
delete from exhibition_outbox where message_type = 'FETCH_PLACE_HOURS';
