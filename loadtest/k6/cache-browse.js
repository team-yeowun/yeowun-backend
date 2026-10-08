// 캐시 on/off 지연 비교 — 사용자 탐색 루프(목록 → 상세 → 목록).
//
//   k6 run -e BASE=http://127.0.0.1:18080 -e VUS=10 -e WARMUP=30 -e DURATION=120 -e OUT=summary.json cache-browse.js
//
// 모델: 고정 VU 폐쇄 모델, 생각 시간 0(THINK_MS로 조절). 워밍업 시나리오 뒤에 측정 시나리오가 이어지고,
//       통계는 측정 시나리오의 요청만 담는다(워밍업은 JIT·커넥션 풀·버퍼풀·Cache-Aside 적재용).
//
// 목록은 프론트 탐색 화면(ExhibitionList.jsx, PAGE_SIZE=20)이 보내는 첫 페이지 그대로다.
//   ?sort=latest|ending|popular&size=20 → ExhibitionListCacheResolver가 ExploreLatestP1·ExploreEndingP1·
//   ExplorePopularP1로 판정하는 요청(캐시 대상). 지역·검색어·커서·홈 섹션(size=2/5)은 캐시 대상이 아니라 넣지 않는다.
// 상세는 방금 받은 목록에서 고른 id(사용자가 목록에서 누르는 전시)다.
import http from 'k6/http';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';
import { sleep } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const VUS = Number(__ENV.VUS || 10);
const WARMUP = Number(__ENV.WARMUP || 30);
const DURATION = Number(__ENV.DURATION || 120);
const THINK_MS = Number(__ENV.THINK_MS || 0);
const SORTS = ['latest', 'ending', 'popular'];

export const options = {
	discardResponseBodies: false,
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
	scenarios: {
		warmup: {
			executor: 'constant-vus', vus: VUS, duration: `${WARMUP}s`, gracefulStop: '0s',
			tags: { phase: 'warmup' },
		},
		measure: {
			executor: 'constant-vus', vus: VUS, duration: `${DURATION}s`, startTime: `${WARMUP}s`,
			gracefulStop: '10s', tags: { phase: 'measure' },
		},
	},
};

// 측정 구간 전용 지표. 요청은 tags.name으로도 갈리지만(http_req_duration{name:list}),
// 워밍업을 확실히 빼려고 측정 시나리오에서만 값을 넣는 Trend를 따로 둔다.
const lat = { list: new Trend('lat_list', true), detail: new Trend('lat_detail', true) };
const latBySort = {};
for (const s of SORTS) latBySort[s] = new Trend(`lat_list_${s}`, true);
const reqs = { list: new Counter('req_list'), detail: new Counter('req_detail') };
const errs = { list: new Counter('err_list'), detail: new Counter('err_detail') };

// VU별 고정 시드 PRNG(mulberry32) — 상세 id 선택이 런 사이에 같은 분포를 갖도록.
function rngOf(seed) {
	let a = seed >>> 0;
	return function () {
		a |= 0; a = (a + 0x6D2B79F5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}
let rng = null;

function record(kind, res, measuring, sort) {
	if (!measuring) return;
	reqs[kind].add(1);
	if (res.status !== 200) { errs[kind].add(1, { status: String(res.status) }); return; }
	lat[kind].add(res.timings.duration);
	if (sort) latBySort[sort].add(res.timings.duration);
}

function think() { if (THINK_MS > 0) sleep(THINK_MS / 1000); }

export default function () {
	if (rng === null) rng = rngOf(20261009 + exec.vu.idInTest);
	const measuring = exec.scenario.name === 'measure';
	const sort = SORTS[(exec.vu.idInTest + exec.vu.iterationInScenario) % SORTS.length];
	const listUrl = `${BASE}/api/v1/exhibitions?sort=${sort}&size=20`;

	// 1) 목록 첫 페이지
	const r1 = http.get(listUrl, { tags: { name: 'list', sort }, timeout: '30s' });
	record('list', r1, measuring, sort);
	let ids = [];
	if (r1.status === 200) {
		try { ids = r1.json('data.content.#.exhibitionId') || []; } catch (e) { ids = []; }
	}
	think();

	// 2) 목록에서 고른 전시의 상세
	if (ids.length > 0) {
		const id = ids[Math.floor(rng() * ids.length)];
		const r2 = http.get(`${BASE}/api/v1/exhibitions/${id}`, { tags: { name: 'detail' }, timeout: '30s' });
		record('detail', r2, measuring, null);
		think();
	}

	// 3) 뒤로 가기 — 같은 목록 다시
	const r3 = http.get(listUrl, { tags: { name: 'list', sort }, timeout: '30s' });
	record('list', r3, measuring, sort);
	think();
}

function pick(m) {
	if (!m) return null;
	const v = m.values;
	return {
		count: v.count, avg: v.avg, min: v.min, p50: v.med, p90: v['p(90)'], p95: v['p(95)'], p99: v['p(99)'], max: v.max,
	};
}

export function handleSummary(data) {
	const m = data.metrics;
	const c = (name) => (m[name] ? m[name].values.count : 0);
	const out = {
		label: __ENV.LABEL || '', vus: VUS, warmup_s: WARMUP, duration_s: DURATION, think_ms: THINK_MS,
		list: pick(m.lat_list), detail: pick(m.lat_detail),
		list_by_sort: Object.fromEntries(SORTS.map((s) => [s, pick(m[`lat_list_${s}`])])),
		requests: { list: c('req_list'), detail: c('req_detail') },
		errors: { list: c('err_list'), detail: c('err_detail') },
		rps_measure: (c('req_list') + c('req_detail')) / DURATION,
		http_reqs_total_incl_warmup: c('http_reqs'),
	};
	const f = (x) => (x == null ? '-' : x.toFixed(2));
	const line = (k, s) => `${k.padEnd(7)} n=${s ? s.count : 0} p50=${f(s && s.p50)} p95=${f(s && s.p95)} p99=${f(s && s.p99)} max=${f(s && s.max)} ms`;
	const text = [
		`[${out.label}] measure ${DURATION}s after ${WARMUP}s warmup, VUs=${VUS}`,
		line('list', out.list), line('detail', out.detail),
		`errors list=${out.errors.list} detail=${out.errors.detail}  rps=${out.rps_measure.toFixed(1)}`,
		'',
	].join('\n');
	const files = { stdout: text };
	if (__ENV.OUT) files[__ENV.OUT] = JSON.stringify(out, null, 2);
	return files;
}
