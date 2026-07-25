-- 절단 플래그 제거 — 이 수집의 목표가 "전량 정합"에서 **신규 등록 포착**으로 좁혀졌다(사용자 결정).
-- truncated는 "원천을 다 가져왔나"를 묻는 값이라, 그 물음을 더는 하지 않기로 한 이상 채울 의미가 없다.
-- max-items 상한 자체는 폭주 방지로 남지만, 상한에 걸린 사실을 배치 단위로 보고하지는 않는다.
-- total_count는 존치한다 — 원천 규모의 추이는 여전히 볼 값이다.
alter table ingestion_run drop column truncated;
