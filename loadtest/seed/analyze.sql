-- 대량 적재 후 옵티마이저 통계 갱신. 빠뜨리면 EXPLAIN의 rows가 실제와 자릿수까지 어긋난다.
ANALYZE TABLE exhibitions;
ANALYZE TABLE exhibition_place;
ANALYZE TABLE exhibition_detail;
ANALYZE TABLE exhibition_genre;
