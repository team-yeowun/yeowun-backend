// R1(보조 VU1) · R2(본체) · R3(시나리오) · R4(상세) 를 한 스크립트에서 돌린다.
//   k6 run -e RUN=r2 -e VUS=4 -e POOLS=... -e OUT=... loadtest/k6/main.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import {
	BASE, PATHS, TIER1, TIER2, R1_PATHS, buildPlan, rngOf, call, available, TREND_STATS,
} from './paths.js';

const RUN = (__ENV.RUN || 'r2').toLowerCase();
const VUS = Number(__ENV.VUS || 4);
const SEED = Number(__ENV.SEED || 20260728);
const N_T1 = Number(__ENV.T1 || 2000);
const N_T2 = Number(__ENV.T2 || 400);
const N_R1 = Number(__ENV.R1N || 150);
const N_V1 = Number(__ENV.V1N || 2000);
const N_SC = Number(__ENV.SCN || 300);

function planFor(run) {
	if (run === 'r1') {
		const spec = {};
		for (const id of R1_PATHS) spec[id] = N_R1;
		return buildPlan(spec, SEED);
	}
	if (run === 'r2') {
		const spec = {};
		for (const id of TIER1) spec[id] = N_T1;
		for (const id of TIER2) spec[id] = N_T2;
		return buildPlan(spec, SEED);
	}
	if (run === 'r4') return buildPlan({ V1: N_V1 }, SEED);
	return [];
}

const PLAN = new SharedArray(`plan_${RUN}`, () => planFor(RUN));

const ITER = RUN === 'r3' ? N_SC : PLAN.length;
const RUN_VUS = RUN === 'r1' ? 1 : VUS;

export const options = {
	discardResponseBodies: false,
	summaryTrendStats: TREND_STATS,
	scenarios: {
		[RUN]: {
			executor: 'shared-iterations',
			vus: RUN_VUS,
			iterations: ITER,
			maxDuration: __ENV.MAX_DURATION || '90m',
		},
	},
};

// SC2 — 탐색 스크롤 3페이지(커서 이어받기). 여기서만 본문 파싱을 허용한다.
const scPage = [new Trend('sc2_p0', true), new Trend('sc2_p1', true), new Trend('sc2_p2', true)];
const scTotal = new Trend('sc2_total', true);

function scenarioScroll(rng) {
	let cursor = null;
	let sum = 0;
	for (let page = 0; page < 3; page++) {
		const url = `${BASE}/api/v1/exhibitions?sort=latest&size=20` + (cursor ? `&cursor=${cursor}` : '');
		const r = http.get(url, { tags: { path: `SC2.p${page}` }, timeout: '30s' });
		scPage[page].add(r.timings.duration);
		sum += r.timings.duration;
		if (r.status !== 200) break;
		try {
			cursor = r.json('data.nextCursor');
		} catch (e) {
			cursor = null;
		}
		if (!cursor) break;
	}
	scTotal.add(sum);
}

export default function () {
	const i = exec.scenario.iterationInTest;
	const rng = rngOf(SEED + i);
	if (RUN === 'r3') {
		scenarioScroll(rng);
		return;
	}
	call(PLAN[i], rng);
}

export function handleSummary(data) {
	const out = { run: RUN, vus: RUN_VUS, iterations: ITER, seed: SEED, metrics: {} };
	for (const [k, v] of Object.entries(data.metrics)) {
		if (k.startsWith('lat_') || k.startsWith('sc2_') || k === 'http_req_duration'
			|| k === 'http_reqs' || k === 'iteration_duration' || k === 'path_errors'
			|| k === 'http_req_failed' || k === 'dropped_iterations') {
			out.metrics[k] = v.values;
		}
	}
	const path = __ENV.OUT || `k6-${RUN}.json`;
	const res = {};
	res[path] = JSON.stringify(out, null, 2);
	res.stdout = `\n[${RUN}] vus=${RUN_VUS} iters=${ITER} reqs=${(data.metrics.http_reqs || {}).values?.count ?? '?'
		} rate=${Math.round((data.metrics.http_reqs || {}).values?.rate ?? 0)}/s\n`;
	return res;
}
