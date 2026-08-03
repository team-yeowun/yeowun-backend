// 28경로 정의 + 요청 계획(plan) 생성. main.js·calibrate.js·distance.js가 공유한다.
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';

export const BASE = __ENV.BASE || 'http://localhost:8080';
export const POOLS = JSON.parse(open(__ENV.POOLS || './pools.json'));

const E = encodeURIComponent;
const P = '/api/v1/exhibitions';

// ── 경로 정의 ────────────────────────────────────────────────────────────────
// 값이 고정인 경로는 고정으로 둔다. 랜덤화는 버퍼풀이 100% 웜이 되는 걸 막는 용도일 뿐,
// 계획이 갈리는 값(region 선택도·keyword 매칭률)을 섞으라는 뜻이 아니다.
export const PATHS = {
	// A. 홈 — 앱이 실제로 보내는 요청(size 실제값)
	H1: () => `${P}?section=ending-soon&size=2`,
	H2: () => `${P}?section=free&size=2`,
	H3: () => `${P}?section=opening-this-month&size=5`,
	H4: () => `${P}/banners`,

	// B. 정렬축 — 같은 데이터, 정렬만 다름
	S1: () => `${P}?sort=latest&size=20`,
	S2: () => `${P}?sort=ending&size=20`,
	S3: () => `${P}?sort=popular&size=20`,
	S4: (rng) => {
		const c = POOLS.coords[Math.floor(rng() * POOLS.coords.length)];
		const j = () => (rng() - 0.5) * 0.02;
		return `${P}?sort=distance&lat=${(c.lat + j()).toFixed(6)}&lng=${(c.lng + j()).toFixed(6)}&size=20`;
	},

	// C. 섹션 × 정렬
	X1: () => `${P}?section=ending-soon&sort=latest&size=20`,
	X2: () => `${P}?section=ending-soon&sort=ending&size=20`,
	X3: () => `${P}?section=ending-soon&sort=popular&size=20`,
	X4: () => `${P}?section=opening-this-month&sort=latest&size=20`,
	X5: () => `${P}?section=opening-this-month&sort=ending&size=20`,
	X6: () => `${P}?section=opening-this-month&sort=popular&size=20`,
	X7: () => `${P}?section=free&sort=latest&size=20`,
	X8: () => `${P}?section=free&sort=ending&size=20`,
	X9: () => `${P}?section=free&sort=popular&size=20`,

	// D. 필터축 — 선택도가 다른 값은 경로를 나눠 고정한다(섞으면 p95가 이중봉)
	F1: () => `${P}?region=SEOUL&size=20`,
	F2: () => `${P}?region=JEJU&size=20`,
	F3: () => `${P}?category=PAINTING&size=20`,
	F4: () => `${P}?region=SEOUL&category=PAINTING&size=20`,

	// E. 검색 — 매칭률 대역별 고정 토큰
	K1: () => `${P}?keyword=${E(POOLS.keywords.high)}&size=20`,
	K2: () => `${P}?keyword=${E(POOLS.keywords.rare)}&size=20`,

	// F. 커서 깊이
	D10: () => `${P}?sort=latest&size=20&cursor=${POOLS.cursors.p10}`,
	D50: () => `${P}?sort=latest&size=20&cursor=${POOLS.cursors.p50}`,
	D100: () => `${P}?sort=latest&size=20&cursor=${POOLS.cursors.p100}`,

	// G. 상세 · 대조군
	V1: (rng) => `${P}/${POOLS.detailIds[Math.floor(rng() * POOLS.detailIds.length)]}`,
	Z1: () => `${P}/region-groups`,
};

export const ALL_IDS = Object.keys(PATHS);

// 티어 — 표본 수가 다르다. p99는 티어1에서만 의미가 있다.
export const TIER1 = ['H2', 'H4', 'S1', 'S2', 'S3', 'X8', 'K1', 'D100'];
export const TIER2 = ['H1', 'H3', 'X1', 'X2', 'X3', 'X4', 'X5', 'X6', 'X7', 'X9',
	'F1', 'F2', 'F3', 'F4', 'K2', 'D10', 'D50', 'Z1'];
// R1(VU 1) 보조 측정 — EXPLAIN 옆에 붙는 열이라 전 경로가 필요 없다.
export const R1_PATHS = ['H2', 'H4', 'S1', 'S2', 'S3', 'X8', 'K1', 'D100', 'F1', 'Z1'];

/** 커서가 없는 볼륨(313)에서는 해당 경로를 계획에서 뺀다. */
export function available(id) {
	if (id === 'D10') return !!POOLS.cursors.p10;
	if (id === 'D50') return !!POOLS.cursors.p50;
	if (id === 'D100') return !!POOLS.cursors.p100;
	return true;
}

// ── 시드 고정 PRNG (mulberry32) ──────────────────────────────────────────────
// Math.random() 금지 — 볼륨 간 같은 요청 열이 나와야 비교가 성립한다.
export function rngOf(seed) {
	let a = seed >>> 0;
	return function () {
		a |= 0; a = (a + 0x6D2B79F5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

/** 경로 ID를 목표 횟수만큼 넣고 고정 시드로 섞는다 → 경로별 표본이 정확히 채워진다. */
export function buildPlan(spec, seed) {
	const plan = [];
	for (const [id, n] of Object.entries(spec)) {
		if (!available(id)) continue;
		for (let i = 0; i < n; i++) plan.push(id);
	}
	const rng = rngOf(seed);
	for (let i = plan.length - 1; i > 0; i--) {
		const j = Math.floor(rng() * (i + 1));
		[plan[i], plan[j]] = [plan[j], plan[i]];
	}
	return plan;
}

// ── 메트릭 — 경로별 Trend를 따로 둔다(태그만으로는 요약에서 갈리지 않는다) ──
const trends = {};
for (const id of ALL_IDS) trends[id] = new Trend(`lat_${id}`, true);
export const errors = new Counter('path_errors');

export function call(id, rng) {
	const url = BASE + PATHS[id](rng);
	const res = http.get(url, {
		tags: { path: id },
		timeout: __ENV.TIMEOUT || '30s',
	});
	trends[id].add(res.timings.duration);
	// 측정 루프에서 본문 파싱 금지 — k6 CPU를 먹는다. status만 본다.
	if (res.status !== 200) errors.add(1, { path: id, status: String(res.status) });
	return res;
}

export const TREND_STATS = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];
