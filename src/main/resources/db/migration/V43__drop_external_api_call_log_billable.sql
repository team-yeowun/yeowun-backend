-- 비용 귀속 플래그 제거 — 비용을 행 단위로 귀속하지 않기로 했다(사용자 결정).
-- 유료 여부는 api 값에서 이미 파생된다(GOOGLE=유료, 나머지는 무료/무료한도). 같은 사실을 컬럼으로 한 번 더
-- 들고 다닐 이유가 없었고, 실제로 쓰기만 되고 읽는 코드·질의가 하나도 없었다(write-only 컬럼).
-- 비용 추이를 다시 보고 싶어지면 api 축으로 집계해라 — idx_external_api_call_api_called가 그 질의를 이미 받는다.
alter table external_api_call_log drop column billable;
