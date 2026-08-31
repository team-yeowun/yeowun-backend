-- 격리 행에 낙관적 잠금 열을 둔다.
--
-- 재주입(redrive)은 관리자가 목록을 보고 누르는 동작이라 같은 행에 요청이 겹칠 수 있다. 지금은 상태 검사(PENDING 인가)와
-- 상태 변경(REPLAYED 로) 사이에 잠금이 없어, 두 요청이 같은 행을 함께 통과하면 아웃박스에 같은 사실이 두 번 적재된다.
-- version 열이 그 창을 닫는다 - 늦은 쪽의 UPDATE 가 0행이 되어 충돌로 끝나고, 아웃박스 적재까지 함께 되돌아간다.
--
--   default 0  기존 행과 새 행 모두 0 에서 시작한다. 애플리케이션이 값을 쓰지 않아도 스키마가 채운다
--   not null   낙관적 잠금은 값이 비면 성립하지 않는다
--
-- 아웃박스에는 같은 열을 두지 않는다. 선점이 FOR UPDATE SKIP LOCKED 비관 배타라 낙관을 겹칠 이유가 없다.

alter table ingestion_dead_letter
    add column version bigint not null default 0 after resolved_at;
