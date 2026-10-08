# loadtest/cache — 전시 캐시 on/off 응답 시간 비교 하네스

같은 데이터·같은 부하에서 캐시를 끈 런과 켠 런을 번갈아 돌려 목록·상세의 p50/p95/p99를 비교합니다.
캐시 스위치는 `app.cache.enabled`(기본 `true`)이고, 끄면 `CacheManager`가 조회를 항상 미스로 돌려 모든 읽기가 DB로 갑니다(적재도 하지 않음).

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.cachebench.yaml` | 측정 전용 MySQL 8.4 + Redis 7. 기존 `modi-mysql`·`modi-redis`와 겹치지 않게 프로젝트 이름·컨테이너·포트(13306·16379)·볼륨을 따로 씁니다 |
| `run-one.sh` | 런 하나: 앱 재시작 → Redis FLUSHALL → k6(워밍업 + 측정) → MySQL 상태·Prometheus·CPU 스냅샷 → 앱 종료 |
| `run-matrix.sh` | (VU × off/on × 반복) 행렬. 반복마다 off/on 순서를 뒤집습니다 |
| `summarize.py` | 런 디렉터리들을 (볼륨, VU, 모드)로 묶어 중앙값과 범위를 표로 냅니다 |
| `../k6/cache-browse.js` | 사용자 탐색 루프: 목록 첫 페이지 → 그 목록에서 고른 상세 → 같은 목록 다시 |

## 실행

```bash
docker compose -p yeowun-cachebench -f loadtest/cache/compose.cachebench.yaml up -d --wait
./gradlew bootJar -x test

# 첫 기동이 로컬 시드 313건을 적재한다(짧은 스모크 런으로 대신)
MODE=on VUS=2 WARMUP=5 DURATION=10 ./loadtest/cache/run-one.sh loadtest/cache/results/_smoke

# 10만 건으로 증폭(원본 313건 보존) → ANALYZE → MySQL 재시작(버퍼풀 128M 복귀)
docker exec -i cachebench-mysql mysql -uroot -pverysecret mydatabase < loadtest/seed/00_setup.sql
docker exec cachebench-mysql mysql -uroot -pverysecret -e "SET GLOBAL innodb_buffer_pool_size = 2147483648"
MYSQL_CONTAINER=cachebench-mysql ./loadtest/seed/amplify.sh 100000 100
docker exec -i cachebench-mysql mysql -uroot -pverysecret mydatabase < loadtest/seed/analyze.sql
docker restart cachebench-mysql

# 행렬
VUS_LIST="50" REPS=2 WARMUP=30 DURATION=120 ./loadtest/cache/run-matrix.sh v100k
python3 loadtest/cache/summarize.py loadtest/cache/results/<행렬 디렉터리>

# 정리(이 스택의 컨테이너·볼륨만)
docker compose -p yeowun-cachebench -f loadtest/cache/compose.cachebench.yaml down -v
```

## 런 조건

- 앱은 호스트 JVM 한 대로 띄웁니다. 운영 compose와 같은 `-Xmx320m`과 Hikari 기본값(10)을 쓰고, 포트는 18080입니다.
- 외부를 부르는 스케줄러와 동기화는 모두 끕니다. 수집, 릴레이, 정리, 리마인드 백필, 조회수 반영이 대상이고, AI·지도 키도 비웁니다.
- 랭킹 재계산(5분 cron)도 끕니다. 요청 경로와 무관한 배치가 측정 구간에 끼어 DB를 치지 않게 하려는 것이고, 기동 직후 1회는 그대로 돕니다.
- 런마다 앱을 재시작하고 Redis를 비웁니다. 그래서 on 런은 빈 캐시에서 시작해 워밍업 구간(통계 제외)에 Cache-Aside로 채워집니다.
- MySQL은 런 사이에 재시작하지 않습니다. 두 모드 모두 버퍼풀이 데워진 상태에서 측정됩니다.

## 밟았던 함정

- 홈 섹션(`size=2/5`)과 지역·검색어 필터, 2페이지 이후는 캐시 대상이 아닙니다(`ExhibitionListCacheResolver`). 시나리오의 목록은 캐시되는 탐색 첫 페이지(`sort=latest|ending|popular&size=20`)만 씁니다.
- 폐쇄 모델(생각 시간 0)이라 VU가 같아도 처리량은 모드마다 다릅니다. 결과표에 처리량을 같이 적어야 p95를 바르게 읽을 수 있습니다.
- 상세는 캐시 히트여도 `ExhibitionDetailService.personalize()`가 읽기 트랜잭션이라, 복제본 라우팅(지연 커넥션)이 없는 로컬 구성에서는 커넥션을 잡고 SET·COMMIT을 보냅니다. on 런 상세 p95의 대부분이 이 커넥션 대기입니다.
