// 캘리브레이션 — VU를 얼마까지 올려도 "쿼리 비용"을 재는 것인지 판정한다.
//   k6 run -e VUS=4 -e POOLS=... -e OUT=... loadtest/k6/calibrate.js
//
// 판정
//   · 처리량이 VU 배수의 70% 이상 오르면 여유 있음
//   · Z1(DB를 안 타는 대조군) p95가 10% 이상 오르면 CPU 포화 — 여기가 상한선
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { TIER2, buildPlan, rngOf, call, TREND_STATS } from './paths.js';

const VUS = Number(__ENV.VUS || 4);
const SEED = Number(__ENV.SEED || 20260728);
const N = Number(__ENV.N || 3000);

const PLAN = new SharedArray('cal', () => {
	const per = Math.max(1, Math.floor(N / TIER2.length));
	const spec = {};
	for (const id of TIER2) spec[id] = per;
	return buildPlan(spec, SEED);
});

export const options = {
	summaryTrendStats: TREND_STATS,
	scenarios: {
		cal: {
			executor: 'shared-iterations',
			vus: VUS,
			iterations: PLAN.length,
			maxDuration: __ENV.MAX_DURATION || '30m',
		},
	},
};

export default function () {
	const i = exec.scenario.iterationInTest;
	call(PLAN[i], rngOf(SEED + i));
}

export function handleSummary(data) {
	const m = data.metrics;
	const out = {
		run: 'calibrate', vus: VUS, iterations: PLAN.length,
		throughput_rps: m.http_reqs?.values?.rate ?? null,
		z1_p95: m.lat_Z1?.values?.['p(95)'] ?? null,
		all_p95: m.http_req_duration?.values?.['p(95)'] ?? null,
		all_p99: m.http_req_duration?.values?.['p(99)'] ?? null,
		failed: m.http_req_failed?.values?.passes ?? 0,
		metrics: {},
	};
	for (const [k, v] of Object.entries(m)) if (k.startsWith('lat_')) out.metrics[k] = v.values;
	const res = {};
	res[__ENV.OUT || `k6-cal-${VUS}.json`] = JSON.stringify(out, null, 2);
	res.stdout = `\n[cal vus=${VUS}] rps=${Math.round(out.throughput_rps)} z1_p95=${(out.z1_p95 || 0).toFixed(1)}ms all_p95=${(out.all_p95 || 0).toFixed(1)}ms\n`;
	return res;
}
