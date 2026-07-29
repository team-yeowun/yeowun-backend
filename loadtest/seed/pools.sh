#!/usr/bin/env bash
# 호출 파라미터 풀 생성 → pools.json
#   · 런 중에는 DB를 치지 않는다. 파라미터 생성 비용이 측정에 섞이면 안 된다.
#   · 커서·id·건수는 데이터가 정하므로 볼륨마다 다시 뽑는다.
#   · 검색어 토큰·좌표·필터 값은 전 볼륨 고정(매칭 수만 기록).
set -euo pipefail

OUT="${1:?usage: pools.sh <out.json>}"
DB=mydatabase
MYSQL=(docker exec -i modi-mysql mysql -uroot -pverysecret --default-character-set=utf8mb4 "$DB")
q() { "${MYSQL[@]}" -N -B 2>/dev/null; }

# ⚠ Cursor.DELIMITER 는 공백이 아니라 **NUL(\0)** 이다(Cursor.java:25).
#    NUL은 명령치환($(...))이 삼키므로 printf → base64 로 파이프해서 만든다.
b64url_cursor() {  # $1=sort $2=key $3=lastId
  printf '%s\000%s\000%s' "$1" "$2" "$3" | base64 | tr '+/' '-_' | tr -d '='
}

# ── P2. 깊은 커서 — S1(sort=latest, 필터 없음)의 정렬·필터를 그대로 재현해 offset으로 뽑는다 ──
cursor_at() {  # $1 = offset
  local row d id
  row=$(q <<SQL
SELECT CONCAT(start_date, '|', id) FROM exhibitions
 WHERE deleted_at IS NULL AND type = 'CATALOG'
   AND start_date IS NOT NULL
   AND start_date <= CURDATE()
   AND (end_date IS NULL OR end_date >= CURDATE())
 ORDER BY start_date DESC, id DESC
 LIMIT 1 OFFSET $1;
SQL
)
  [ -z "$row" ] && { echo ""; return; }
  d=${row%%|*}; id=${row##*|}
  b64url_cursor latest "$d" "$id"
}

C10=$(cursor_at 199)
C50=$(cursor_at 999)
C100=$(cursor_at 1999)

# ── P1. 상세 id 풀 ── (GROUP_CONCAT 기본 상한 1024B라 반드시 올려야 한다)
DETAIL_IDS=$(q <<'SQL'
SET SESSION group_concat_max_len = 33554432;
SELECT GROUP_CONCAT(id) FROM (
  SELECT id FROM exhibitions
   WHERE deleted_at IS NULL AND type='CATALOG'
   ORDER BY id * 2654435761 % 1000003
   LIMIT 5000
) t;
SQL
)

# ── P4. 좌표 풀 ──
COORDS=$(q <<'SQL'
SET SESSION group_concat_max_len = 33554432;
SELECT GROUP_CONCAT(CONCAT(gps_y, ':', gps_x)) FROM (
  SELECT gps_y, gps_x FROM exhibition_place
   WHERE gps_x IS NOT NULL AND gps_y IS NOT NULL
   ORDER BY id * 2654435761 % 1000003
   LIMIT 200
) t;
SQL
)

# ── P3. 검색어 토큰 — 후보의 실제 매칭 수를 세어 고빈도/희소를 고른다 ──
kw_count() {
  q <<SQL
SELECT COUNT(*) FROM exhibitions e
 WHERE e.deleted_at IS NULL AND e.type='CATALOG'
   AND (e.end_date IS NULL OR e.end_date >= CURDATE())
   AND (LOWER(e.title) LIKE '%$1%'
        OR e.exhibition_place_id IN
           (SELECT p.id FROM exhibition_place p WHERE LOWER(p.name) LIKE '%$1%'));
SQL
}

CANDIDATES=(전시 미술 특별 기획 사진 조각 도자 공예 판화 서예)
declare -a KW_NAME KW_CNT
for c in "${CANDIDATES[@]}"; do
  n=$(kw_count "$c")
  KW_NAME+=("$c"); KW_CNT+=("$n")
done

HIGH=""; HIGH_N=-1; RARE=""; RARE_N=-1
for i in "${!KW_NAME[@]}"; do
  (( KW_CNT[i] > HIGH_N )) && { HIGH="${KW_NAME[i]}"; HIGH_N="${KW_CNT[i]}"; }
done
for i in "${!KW_NAME[@]}"; do
  n="${KW_CNT[i]}"
  if (( n > 0 && n * 20 <= HIGH_N )); then
    if (( RARE_N < 0 || n < RARE_N )); then RARE="${KW_NAME[i]}"; RARE_N="$n"; fi
  fi
done
[ -z "$RARE" ] && { RARE="${KW_NAME[1]}"; RARE_N="${KW_CNT[1]}"; }

# ── P5. 경로별 totalCount는 앱을 통해 찍는다(Specification과 어긋나지 않게) ──
BASE="${BASE:-http://localhost:8080}"
tc() { curl -s --max-time 60 "$BASE/api/v1/exhibitions?$1&size=1" | sed -n 's/.*"totalCount":\([0-9-]*\).*/\1/p'; }

# bash 3.2(맥 기본)에는 연관배열이 없다 — 평범한 변수로 둔다.
urlenc() { printf '%s' "$1" | LC_ALL=C awk '{n=split($0,c,""); for(i=1;i<=n;i++) printf "%%%02X", 0+ord(c[i])}' 2>/dev/null \
           || python3 -c "import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
T_H1=null; T_H2=null; T_H3=null; T_S1=null; T_F1=null; T_F2=null; T_F3=null; T_F4=null; T_K1=null; T_K2=null
if curl -s --max-time 5 "$BASE/api/v1/exhibitions/region-groups" >/dev/null 2>&1; then
  T_H1=$(tc 'section=ending-soon');            T_H2=$(tc 'section=free')
  T_H3=$(tc 'section=opening-this-month');     T_S1=$(tc 'sort=latest')
  T_F1=$(tc 'region=SEOUL');                   T_F2=$(tc 'region=JEJU')
  T_F3=$(tc 'category=PAINTING');              T_F4=$(tc 'region=SEOUL&category=PAINTING')
  T_K1=$(tc "keyword=$(urlenc "$HIGH")");      T_K2=$(tc "keyword=$(urlenc "$RARE")")
fi
for v in H1 H2 H3 S1 F1 F2 F3 F4 K1 K2; do
  eval "cur=\$T_$v"; [ -z "$cur" ] && eval "T_$v=null"
done

# ── 데이터 분포 실측(결과 문서에 그대로 싣는다) ──
read -r EXH PLACE DETAIL GENRE ONGOING FREEROWS VIEWGT0 <<< "$(q <<'SQL'
SELECT (SELECT COUNT(*) FROM exhibitions),
       (SELECT COUNT(*) FROM exhibition_place),
       (SELECT COUNT(*) FROM exhibition_detail),
       (SELECT COUNT(*) FROM exhibition_genre),
       (SELECT COUNT(*) FROM exhibitions WHERE start_date<=CURDATE() AND end_date>=CURDATE()),
       (SELECT COUNT(*) FROM exhibition_detail WHERE LOWER(price) LIKE '%무료%' OR price='0원'),
       (SELECT COUNT(*) FROM exhibitions WHERE our_view_count>0);
SQL
)"

kw_json="["
for i in "${!KW_NAME[@]}"; do
  kw_json+="{\"token\":\"${KW_NAME[i]}\",\"matches\":${KW_CNT[i]}}"
  (( i < ${#KW_NAME[@]}-1 )) && kw_json+=","
done
kw_json+="]"

tot_json="{\"H1\":${T_H1},\"H2\":${T_H2},\"H3\":${T_H3},\"S1\":${T_S1},\"F1\":${T_F1},\"F2\":${T_F2},\"F3\":${T_F3},\"F4\":${T_F4},\"K1\":${T_K1},\"K2\":${T_K2}}"

cat > "$OUT" <<JSON
{
  "detailIds": [${DETAIL_IDS}],
  "coords": [$(printf '%s' "$COORDS" | awk -F, '{for(i=1;i<=NF;i++){split($i,a,":"); printf "%s{\"lat\":%s,\"lng\":%s}", (i>1?",":""), a[1], a[2]}}')],
  "cursors": { "p10": "${C10}", "p50": "${C50}", "p100": "${C100}" },
  "keywords": { "high": "${HIGH}", "highMatches": ${HIGH_N}, "rare": "${RARE}", "rareMatches": ${RARE_N},
                "candidates": ${kw_json} },
  "totalCounts": ${tot_json},
  "distribution": {
    "exhibitions": ${EXH}, "place": ${PLACE}, "detail": ${DETAIL}, "genre": ${GENRE},
    "ongoing": ${ONGOING}, "freePriceRows": ${FREEROWS}, "viewCountGt0": ${VIEWGT0}
  }
}
JSON

echo "[pools] $OUT — exhibitions=${EXH} ongoing=${ONGOING} free=${FREEROWS} kw:${HIGH}(${HIGH_N})/${RARE}(${RARE_N}) cursors:${C10:+p10}${C50:+,p50}${C100:+,p100}"
