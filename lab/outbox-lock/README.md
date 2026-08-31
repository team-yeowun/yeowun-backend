# 아웃박스 선점 전략 부하 실험 하네스

앱 두 대가 같은 미발행 행을 동시에 집을 때 락 방식이 무엇을 바꾸는지 같은 무대에서 재기 위한 도구다.
프로덕션 코드는 그대로 두고 **프로퍼티 토글만** 바꿔 변형을 만든다(`app.ingestion.v2.claim-strategy` ·
`dispatch-batch-size` · `outbox-read`).

무대는 `compose.lab.yaml` 하나다. 기존 `compose.yaml` 은 건드리지 않으며 프로젝트 이름이 달라서
(`outboxlock`) 볼륨·네트워크가 겹치지 않는다.

```
nginx ─▶ app1 / app2 ──쓰기·선점──▶ mysql-master ──GTID 비동기 복제──▶ mysql-replica
                     └─마커·스트림─▶ redis (maxmemory 2gb, noeviction)
```

## 먼저 한 번

```bash
cd lab/outbox-lock
docker compose -f compose.lab.yaml build app1     # 앱 이미지 한 벌(두 앱이 같은 이미지를 쓴다)
```

## 한 변형 돌리기

```bash
./run.sh s5              # 변형 파일이 정한 모드(regular = 적재 10분 + T_max 3분)
./run.sh s3b short       # 단축 런(적재 1분 + 첫 소진)
./run.sh s5 smoke        # 100행 · 30초 · 틱 2초 — 형식·계기판 확인용
```

`run.sh` 가 하는 일: 인프라 기동 → 복제 확인 → 테이블 truncate + Redis FLUSHALL → **두 앱 동시 기동**
→ before 진위 확인 → 적재 시작 → 10초 간격 표본 → (s5) 마커 삭제 주입 → 상한 도달 → 종료 계기판
→ `run-summary.json` · `run-summary.md` · `attachments/` 봉인.

원시값 위치: `docs/…/problem/01-분산 환경 스케줄러 중복 실행 제어/_workspace/03_measurer_raw/<variant>/`
(smoke 는 `…/03_measurer_raw/_smoke/<variant>/`).

## 변형

| 파일 | 전략 | 배치 | 조회 | 앱 | 모드 |
|---|---|---|---|---|---|
| `s0` | NONE | 0(상한 없음) | MASTER | 2 | regular |
| `s1` | PESSIMISTIC | 0 | MASTER | 2 | regular |
| `s2` | SKIP_LOCKED | 0 | MASTER | 2 | regular |
| `s3a` | SKIP_LOCKED | 500 | MASTER | 2 | regular |
| `s3b` | PESSIMISTIC | 500 | MASTER | 2 | short |
| `s4a` | PESSIMISTIC | 500 | REPLICA | 1 | short |
| `s4b` | NONE | 500 | REPLICA | 1 | short |
| `s5` | REDIS_MARKER | 500 | MASTER | 2 | regular + 마커 삭제 주입 |
| `s5-1` | REDIS_MARKER | 500 | MASTER | 1 | regular |

공통 무대는 `variants/_base.env`(컨슈머 OFF · 틱 60초 · 소진 루프 ON · 트리밍·정리·수집 회차 비활성 ·
미발행 행 계기 OFF · 힙 `-Xmx2g` 고정). 코어 스케줄러(전시 동기화·v1 릴레이·정리·캐시 워밍·조회수 플러시·
리마인드 백필)도 전부 재운다 — 같은 MySQL·Redis 를 두드려 `Com_select`·`commandstats` 델타를 오염시킨다.

## 중복은 무엇으로 세는가

주 지표는 **대기열에 실제로 실린 것**이다. 런이 끝나면 `observe/dump-stream.sh` 가 `XRANGE` 로 스트림을
훑어 payload 의 `aggregateId` 목록을 만들고 `observe/duplicate-count.sh` 가 그것을 센다.

```
중복 = 대기열 항목 수 − 고유 aggregateId 수
```

`Σ발행 성공 − SENT 행수` 는 보조로만 쓴다. 틱 트랜잭션이 롤백되면 XADD 는 되돌아가지 않고 카운터도 이미
올라간 채 행만 미발행으로 돌아오므로, 롤백된 발행까지 중복으로 세기 때문이다. 두 값이 갈리면 그 차이가
곧 롤백된 미확정 발행이고, `Com_commit`/`Com_rollback` 델타가 그 해석의 근거다.

100만 항목의 `XRANGE` 원문은 수백 MB라 통째로 남기지 않는다. 남는 것은 셋이다 —
`stream-aggregate-ids.txt.gz`(전량 목록) · `stream-duplicate-ids.txt`(두 번 이상 나간 id, 주입 구간 대조용) ·
`stream-dump-sample.txt`(첫 페이지 원문 300줄, 레코드 형식의 증거).

## 파일

| 경로 | 역할 |
|---|---|
| `compose.lab.yaml` | 무대 |
| `mysql/{master,replica}.cnf` · `mysql/init-replication.sh` | GTID 비동기 복제. 복제본의 읽기 전용 잠금은 cnf 가 아니라 스크립트가 건다(cnf 에 박으면 컨테이너 초기화가 자기 자신에게 막힌다) |
| `nginx/nginx.conf` | 운영 구성 재현용. 측정 경로가 아니다 — 앱 두 대일 때만 뜬다 |
| `seed/seed-loop.sh` | 적재기. 분당 목표를 열 덩어리로 나눠 넣고 `loader.jsonl` 에 시각을 남긴다 |
| `seed/smoke-gate.sh` | 이미 떠 있는 무대에 100행을 넣어 형식을 확인하는 독립 게이트(`run.sh <v> smoke` 가 같은 확인을 포함한다) |
| `variants/*.env` | 토글 |
| `run.sh` | 한 변형 전체 |
| `inject-marker-loss.sh` | 락이 사라진 상태 주입(장애 재현이 아니다) |
| `observe/*.sh` | 계기판 — 앱 스크랩 · MySQL STATUS · 복제 지연 · Redis INFO · INNODB STATUS · docker stats · 스트림 덤프·중복 집계 |

## 주의

- 런 중에는 `COUNT(*)` 를 돌리지 않는다. 관측이 부하를 바꾼다. 상태별 행수는 런이 끝난 뒤 한 번만 센다.
- Redis 는 `noeviction` 이다. `used_memory` 가 1.5GB 를 넘으면 마커 `SET` 이 실패해 발송이 멈춘다 —
  런을 중단하고 상한을 올린 뒤 그 사실을 조건에 남긴다.
- 상한 없는 변형(s0·s1·s2)은 앱 OOM 이나 락 대기 타임아웃으로 끝날 수 있다. 그것도 결과다 —
  힙 덤프(`/tmp`)와 그 시점의 미발행 행수를 남기고 그 지점까지의 처리량으로 마감한다.
- 스크랩(`/actuator/prometheus`)은 대기열 상태 계기 때문에 Redis 에 `XLEN`·`XINFO GROUPS` 를 쏜다.
  `INFO commandstats` 대조에서 `cmdstat_set` 외의 항목은 이 스크랩이 섞여 있다.
