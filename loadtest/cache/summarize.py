#!/usr/bin/env python3
"""캐시 on/off 행렬 결과 요약.

    python3 loadtest/cache/summarize.py loadtest/cache/results/<matrix-dir> [...]

런 디렉터리마다 k6-summary.json · mysql-status-{start,end}.tsv · prom-{start,end}.txt ·
docker-stats.csv · app-cpu.csv 를 읽어 (볼륨 라벨, VU, 모드) 묶음별 중앙값과 최소~최대를 낸다.
히트율·쿼리 수는 측정 구간(워밍업 직후 스냅샷 ~ k6 종료 스냅샷) 델타다.
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

LOOKUP = re.compile(r'^modi_cache_lookup_total\{[^}]*cache="([^"]+)"[^}]*result="([^"]+)"[^}]*\}\s+([0-9.eE+]+)')


def prom_lookups(path):
    out = defaultdict(float)
    if not path.exists():
        return out
    for line in path.read_text().splitlines():
        m = LOOKUP.match(line)
        if m:
            out[(m.group(1), m.group(2))] = float(m.group(3))
    return out


def mysql_status(path):
    d = {}
    if path.exists():
        for line in path.read_text().splitlines():
            parts = line.split()
            if len(parts) == 2:
                d[parts[0]] = int(parts[1])
    return d


def cpu_avg(path, name=None):
    if not path.exists():
        return None
    vals = []
    for line in path.read_text().splitlines()[1:]:
        cols = line.split(',')
        if name is not None:
            if len(cols) < 3 or cols[1] != name:
                continue
            vals.append(float(cols[2].rstrip('%')))
        else:
            if len(cols) >= 2 and cols[1]:
                vals.append(float(cols[1]))
    return statistics.mean(vals) if vals else None


def run_row(d):
    k = json.loads((d / 'k6-summary.json').read_text())
    meta = json.loads((d / 'meta.json').read_text())
    s0, s1 = mysql_status(d / 'mysql-status-start.tsv'), mysql_status(d / 'mysql-status-end.tsv')
    delta = {key: s1.get(key, 0) - s0.get(key, 0) for key in s1}
    p0, p1 = prom_lookups(d / 'prom-start.txt'), prom_lookups(d / 'prom-end.txt')
    look = defaultdict(float)
    for key in set(p0) | set(p1):
        look[key] = p1.get(key, 0) - p0.get(key, 0)

    def ratio(caches):
        hit = sum(v for (c, r), v in look.items() if c in caches and r in ('l1_hit', 'l2_hit'))
        tot = sum(v for (c, r), v in look.items() if c in caches)
        return (hit / tot) if tot else None

    reqs = k['requests']['list'] + k['requests']['detail']
    return {
        'dir': d.name, 'mode': meta['mode'], 'vus': meta['vus'], 'rows': meta['exhibitions'],
        'list': k['list'], 'detail': k['detail'],
        'err_list': k['errors']['list'], 'err_detail': k['errors']['detail'],
        'req_list': k['requests']['list'], 'req_detail': k['requests']['detail'],
        'rps': k['rps_measure'],
        'hit_list': ratio({'ExploreLatestP1', 'ExploreEndingP1', 'ExplorePopularP1'}),
        'hit_detail': ratio({'ExhibitionDetail'}),
        'lookups': sum(look.values()),
        'q_per_req': delta.get('Questions', 0) / reqs if reqs else None,
        'sel_per_req': delta.get('Com_select', 0) / reqs if reqs else None,
        'mysql_cpu': cpu_avg(d / 'docker-stats.csv', 'cachebench-mysql'),
        'redis_cpu': cpu_avg(d / 'docker-stats.csv', 'cachebench-redis'),
        'app_cpu': cpu_avg(d / 'app-cpu.csv'),
    }


def agg(vals, fmt='{:.2f}'):
    vals = [v for v in vals if v is not None]
    if not vals:
        return '-'
    med = statistics.median(vals)
    if len(vals) == 1:
        return fmt.format(med)
    return f"{fmt.format(med)} ({fmt.format(min(vals))}–{fmt.format(max(vals))})"


def main(dirs):
    rows = []
    for root in dirs:
        root = Path(root)
        for d in sorted(root.iterdir()):
            if d.is_dir() and (d / 'k6-summary.json').exists() and (d / 'meta.json').exists():
                r = run_row(d)
                r['label'] = root.name
                rows.append(r)
    groups = defaultdict(list)
    for r in rows:
        groups[(r['label'], r['rows'], r['vus'], r['mode'])].append(r)

    print('| 볼륨(행) | VU | 모드 | 런 | 목록 p50 | 목록 p95 | 목록 p99 | 상세 p50 | 상세 p95 | 상세 p99 | 처리량(req/s) | 오류 |')
    print('|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
    for (label, n, vus, mode), rs in sorted(groups.items(), key=lambda x: (x[0][1], x[0][2], x[0][3] != 'off')):
        errs = sum(r['err_list'] + r['err_detail'] for r in rs)
        tot = sum(r['req_list'] + r['req_detail'] for r in rs)
        print(f"| {n:,} | {vus} | {mode} | {len(rs)} | "
              + ' | '.join(agg([r[k][p] for r in rs]) for k in ('list', 'detail') for p in ('p50', 'p95', 'p99'))
              + f" | {agg([r['rps'] for r in rs], '{:.0f}')} | {errs}/{tot} |")
    print()
    print('| 볼륨(행) | VU | 모드 | 목록 히트율 | 상세 히트율 | MySQL 질의/요청 | SELECT/요청 | MySQL CPU % | Redis CPU % | 앱 JVM CPU % |')
    print('|---|---:|---|---:|---:|---:|---:|---:|---:|---:|')
    for (label, n, vus, mode), rs in sorted(groups.items(), key=lambda x: (x[0][1], x[0][2], x[0][3] != 'off')):
        pct = lambda key: agg([None if r[key] is None else r[key] * 100 for r in rs], '{:.1f}')
        print(f"| {n:,} | {vus} | {mode} | {pct('hit_list')} | {pct('hit_detail')} | "
              f"{agg([r['q_per_req'] for r in rs])} | {agg([r['sel_per_req'] for r in rs])} | "
              f"{agg([r['mysql_cpu'] for r in rs], '{:.0f}')} | {agg([r['redis_cpu'] for r in rs], '{:.0f}')} | "
              f"{agg([r['app_cpu'] for r in rs], '{:.0f}')} |")
    print()
    print('런별 원자료:')
    for r in rows:
        print(f"  {r['label']}/{r['dir']}: list p95={r['list']['p95']:.2f} detail p95={r['detail']['p95']:.2f} "
              f"rps={r['rps']:.0f} lookups={r['lookups']:.0f}")


if __name__ == '__main__':
    main(sys.argv[1:])
