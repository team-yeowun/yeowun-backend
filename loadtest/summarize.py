#!/usr/bin/env python3
"""run 디렉토리들을 읽어 볼륨별 지연 표를 만든다(마크다운).

    python3 loadtest/summarize.py loadtest/results/*/

셀은 "값(n)" 형식이다 — 표본이 얇은 칸을 숨기지 않기 위해서다.
"""
import json
import os
import sys

PATH_LABEL = {
    'H1': '곧 끝남 · 최신순 (size2)', 'H2': '무료 · 최신순 (size2)',
    'H3': '이번 달 개막 · 최신순 (size5)', 'H4': '배너 · 조회수순',
    'S1': '최신순 · 무필터', 'S2': '종료순 · 무필터', 'S3': '인기순 · 무필터',
    'S4': '거리순 (격리 런)',
    'X1': '곧 끝남 × 최신순', 'X2': '곧 끝남 × 종료순', 'X3': '곧 끝남 × 인기순',
    'X4': '이번달 × 최신순', 'X5': '이번달 × 종료순', 'X6': '이번달 × 인기순',
    'X7': '무료 × 최신순', 'X8': '무료 × 종료순', 'X9': '무료 × 인기순',
    'F1': '지역=서울 · 최신순', 'F2': '지역=제주 · 최신순',
    'F3': '분류=회화 · 최신순', 'F4': '서울+회화 · 최신순',
    'K1': '검색 고빈도 · 최신순', 'K2': '검색 희소 · 최신순',
    'D10': '커서 10p · 최신순', 'D50': '커서 50p · 최신순', 'D100': '커서 100p · 최신순',
    'V1': '상세', 'Z1': '정적(대조군)',
}
ORDER = ['H1', 'H2', 'H3', 'H4', 'S1', 'S2', 'S3', 'S4',
         'X1', 'X2', 'X3', 'X4', 'X5', 'X6', 'X7', 'X8', 'X9',
         'F1', 'F2', 'F3', 'F4', 'K1', 'K2', 'D10', 'D50', 'D100', 'V1', 'Z1']


def load(d):
    meta = json.load(open(os.path.join(d, 'meta.json')))
    pools = json.load(open(os.path.join(d, 'pools.json')))
    runs = {}
    for r in ('r1', 'r2', 'r3', 'r4', 'r5'):
        p = os.path.join(d, f'k6-{r}.json')
        if os.path.exists(p):
            runs[r] = json.load(open(p))
    return {'dir': d, 'meta': meta, 'pools': pools, 'runs': runs}


def stat(run, pid, key):
    m = (run or {}).get('metrics', {}).get(f'lat_{pid}')
    return m.get(key) if m else None


def pick(res, pid, key):
    """R2(본체) 우선, 없으면 R4(상세)·R5(거리순)·R1."""
    for r in ('r2', 'r4', 'r5', 'r1'):
        v = stat(res['runs'].get(r), pid, key)
        if v is not None:
            return v, r
    return None, None


def cell(res, pid, key):
    v, _ = pick(res, pid, key)
    n, _ = pick(res, pid, 'count')
    if v is None or not n:
        return 'N/A'
    return f'{v:.0f} ({int(n)})'


def main(dirs):
    rs = sorted((load(d.rstrip('/')) for d in dirs), key=lambda r: r['meta']['volume'])
    vols = [f"{r['meta']['volume']:,}" for r in rs]

    print('### 볼륨별 지연 — p50 (ms, 괄호는 표본 수)\n')
    print('| ID | 경로 | ' + ' | '.join(vols) + ' | 배율 |')
    print('|---|---|' + '---:|' * (len(vols) + 1))
    for pid in ORDER:
        cells = [cell(r, pid, 'med') for r in rs]
        first, last = pick(rs[0], pid, 'med')[0], pick(rs[-1], pid, 'med')[0]
        ratio = f'{last / first:.1f}×' if first and last and first > 0 else '—'
        print(f'| **{pid}** | {PATH_LABEL[pid]} | ' + ' | '.join(cells) + f' | {ratio} |')

    print('\n### 볼륨별 지연 — p95 (ms, 괄호는 표본 수)\n')
    print('| ID | 경로 | ' + ' | '.join(vols) + ' |')
    print('|---|---|' + '---:|' * len(vols))
    for pid in ORDER:
        print(f'| **{pid}** | {PATH_LABEL[pid]} | ' + ' | '.join(cell(r, pid, 'p(95)') for r in rs) + ' |')

    print('\n### 보조 측정 R1 (VU 1 — 큐잉 0, 순수 쿼리 시간) p50\n')
    print('| ID | ' + ' | '.join(vols) + ' |')
    print('|---|' + '---:|' * len(vols))
    for pid in ORDER:
        vals = []
        any_ = False
        for r in rs:
            v = stat(r['runs'].get('r1'), pid, 'med')
            n = stat(r['runs'].get('r1'), pid, 'count')
            vals.append(f'{v:.0f} ({int(n)})' if v is not None and n else '—')
            any_ = any_ or v is not None
        if any_:
            print(f'| **{pid}** | ' + ' | '.join(vals) + ' |')

    print('\n### 모집단(totalCount) · 데이터 분포\n')
    keys = ['H1', 'H2', 'H3', 'S1', 'F1', 'F2', 'F3', 'F4', 'K1', 'K2']
    print('| 항목 | ' + ' | '.join(vols) + ' |')
    print('|---|' + '---:|' * len(vols))
    for k in keys:
        print(f'| {k} totalCount | ' + ' | '.join(
            f"{(r['pools']['totalCounts'].get(k) or 0):,}" for r in rs) + ' |')
    for k, lab in (('exhibitions', '전시 행'), ('place', '전시장 행'),
                   ('ongoing', '진행 중'), ('freePriceRows', '무료 가격 행'),
                   ('viewCountGt0', '조회수>0')):
        print(f'| {lab} | ' + ' | '.join(f"{r['pools']['distribution'][k]:,}" for r in rs) + ' |')
    print('| 검색어(고빈도/희소) | ' + ' | '.join(
        f"{r['pools']['keywords']['high']}({r['pools']['keywords']['highMatches']:,})"
        f" / {r['pools']['keywords']['rare']}({r['pools']['keywords']['rareMatches']:,})" for r in rs) + ' |')

    print('\n### 시나리오 SC2 (탐색 스크롤 3페이지)\n')
    print('| 항목 | ' + ' | '.join(vols) + ' |')
    print('|---|' + '---:|' * len(vols))
    for k in ('sc2_p0', 'sc2_p1', 'sc2_p2', 'sc2_total'):
        row = []
        for r in rs:
            m = r['runs'].get('r3', {}).get('metrics', {}).get(k)
            row.append(f"{m['med']:.0f} ({int(m['count'])})" if m else '—')
        print(f'| {k} p50 | ' + ' | '.join(row) + ' |')

    print('\n### 처리량 · 실패\n')
    print('| 항목 | ' + ' | '.join(vols) + ' |')
    print('|---|' + '---:|' * len(vols))
    for r_id in ('r1', 'r2'):
        row = []
        for r in rs:
            m = r['runs'].get(r_id, {}).get('metrics', {})
            rate = (m.get('http_reqs') or {}).get('rate')
            row.append(f'{rate:.1f}/s' if rate else '—')
        print(f'| {r_id} 처리량 | ' + ' | '.join(row) + ' |')
    row = []
    for r in rs:
        m = r['runs'].get('r2', {}).get('metrics', {})
        f = (m.get('http_req_failed') or {}).get('passes')
        row.append(str(int(f)) if f is not None else '—')
    print('| r2 실패 요청 | ' + ' | '.join(row) + ' |')


if __name__ == '__main__':
    main(sys.argv[1:] or ['loadtest/results'])
