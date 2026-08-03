# loadtest — 전시 조회 볼륨별 지연 측정 하네스

볼륨(313 → 10만 → 50만 → 100만)을 바꿔가며 전시 조회 경로의 지연을 재고, 인덱스를 하나 걸 때마다 되돌아와 다시 돌리기 위한 회귀 하네스입니다.

- 실험 설계·경로 정의: `docs/개인 폴더/전시읽기최적화/전시_조회_부하실험_실행지시서.md`
- 첫 측정 결과: `docs/개인 폴더/전시읽기최적화/전시_조회_부하실험_결과.md`

## 준비

```bash
docker compose up -d mysql redis     # MySQL 8.4 (버퍼풀 기본 128M · O_DIRECT)
./gradlew bootJar -x test
docker exec -i modi-mysql mysql -uroot -pverysecret mydatabase < loadtest/seed/00_setup.sql
```

`00_setup.sql`은 증폭용 시퀀스(`lt_seq`)와 **원본 경계**(`lt_base`)를 만듭니다. 증폭분은 전부 이 경계 위의 id라 원본 313건은 어떤 경우에도 보존됩니다.

## 실행

```bash
# 볼륨 하나를 끝까지 (증폭 → ANALYZE → MySQL 재시작 → 앱 재시작 → pools → 워밍업 → R1~R5 → EXPLAIN)
VUS=2 T1=120 T2=40 R1N=30 V1N=150 SCN=50 ./loadtest/run.sh 100000 v100k 2

# 인덱스 하나 걸고 빠르게 재측정 (보조 런 + EXPLAIN만)
VUS=2 R1N=50 ./loadtest/run.sh 1000000 after-idx-v1 2

# 결과 비교표
./loadtest/compare.sh                          # results/* 전부
python3 loadtest/summarize.py results/A results/B
```

앱은 **호스트 JVM**(`java -Xmx1g -jar`)으로 뜹니다. MySQL만 Docker VM(2 vCPU) 안에 있어 앱과 CPU를 다투지 않고, 그만큼 측정값이 쿼리 비용에 가깝게 남습니다.

## 런 구성

| 런 | 무엇 | VU | 왜 분리하나 |
| --- | --- | ---: | --- |
| R1 | 관심 10경로 보조 측정 | 1 | 큐잉 0인 **순수 쿼리 시간**. `EXPLAIN` 옆 열 |
| R2 | 26경로 본체 | 2 | p50·p95가 여기서 나온다 |
| R3 | 탐색 스크롤 시나리오 | 2 | 화면 단위 체감 |
| R4 | 상세(쓰기 트랜잭션) | 2 | `our_view_count`를 바꾸므로 인기순 측정 뒤로 |
| R5 | 거리순 격리 | 1 | 한 요청이 수십 초라 섞으면 남의 큐잉을 오염시킨다 |

## 튜닝 손잡이 (환경변수)

| | 뜻 | 기본 |
| --- | --- | ---: |
| `T1` · `T2` | 티어1·티어2 경로당 표본 | 2000 · 400 |
| `R1N` · `V1N` · `SCN` · `DISTN` | R1·상세·시나리오·거리순 반복 | 150 · 2000 · 300 · 50 |
| `RUN_DISTANCE` | 거리순 런 실행 여부 | 1 |
| `MAX_DURATION` | k6 런 상한 | 90m |
| `BATCH_GEN` | 증폭 배치(세대 단위) | 100 |
| `SEED` | 난수 시드 | 20260728 |

## 밟았던 함정 (같은 걸 다시 밟지 않도록)

- **커서 구분자는 공백이 아니라 NUL(`\0`)입니다**(`Cursor.java:25`). 공백으로 만들면 전 요청이 400 `INVALID_CURSOR`인데 응답이 5ms라 **"커서가 빠르다"로 오독**하기 딱 좋습니다. `pools.sh`가 `printf '…\000…'`로 만듭니다.
- **`GROUP_CONCAT` 기본 상한은 1024바이트**입니다. 안 올리면 id 풀이 5,000개가 아니라 172개가 됩니다.
- **외부를 부르는 `curl`에는 반드시 `--max-time`.** 거리순 OOM 뒤 앱 actuator가 응답하지 않아 타임아웃 없는 `curl`이 1시간 26분을 잡아먹은 적이 있습니다.
- **k6 요청에 `tags: {path: id}` 필수.** 없으면 커서·id가 섞인 URL이 서로 다른 메트릭으로 집계돼 경로별 p95가 산산조각 납니다.
- **적재 중에만 버퍼풀을 키웁니다.** `run.sh`가 증폭 전 2G로 올리고, 측정 전 MySQL 재시작으로 128M(기본)으로 되돌립니다.

## 되돌리기

```bash
docker exec -i modi-mysql mysql -uroot -pverysecret mydatabase < loadtest/seed/reset.sql
```

증폭분만 지우고 원본 313건은 남깁니다(멱등). 100만에서 되돌리면 300만 행 삭제라 몇 분 걸립니다.
