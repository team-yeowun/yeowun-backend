package modi.backend.ingestion.domain.port;

import java.util.List;

/**
 * 페이지 순회를 여기서 멈춰도 되는지 판정하는 조건(도메인 소유 포트) — <b>어댑터가 DB를 모른 채</b> 조기 종료할 수 있게 한다.
 *
 * <p><b>왜 건당이 아니라 페이지 단위인가</b>: 건당 {@code Predicate<String>}로 받으면 어댑터가 아이템마다 한 번씩
 * 물어보게 되고, 구현이 DB를 보는 순간 <b>페이지당 100회 조회</b>가 된다 — 조기 종료로 아끼려던 쿼리를 판정에서 도로 쓴다.
 * id 목록을 통째로 넘기면 구현이 {@code IN} 한 번으로 답할 수 있어 <b>페이지당 1회</b>다.
 *
 * <p><b>왜 "전량 known"이어야 멈추나</b>: 원천의 {@code seq}가 등록 순서와 항상 단조라는 보장이 없다(실측만 있고
 * 문서화되지 않은 {@code sortStdr=8}에 기댄다 — 원천이 과거 데이터를 나중에 채워 넣으면 깨진다). 아는 것 <b>하나</b>를
 * 만나 멈추면 그 뒤에 꽂힌 신규를 통째로 놓친다. 페이지 전량이 아는 것일 때만 멈추면 그 위험이 페이지 크기만큼 완충된다.
 */
@FunctionalInterface
public interface CatalogPageStop {

	/**
	 * 이 페이지의 항목이 <b>전부</b> 이미 아는 것인가 — true면 어댑터가 다음 페이지를 부르지 않는다.
	 *
	 * @param externalIds 방금 받은 페이지의 원천 식별자(응답 순서 그대로, 필터 이전)
	 */
	boolean allKnown(List<String> externalIds);

	/** 한 번도 멈추지 않는다 — 전량 순회(조기 종료를 끄고 싶을 때·테스트). */
	static CatalogPageStop never() {
		return externalIds -> false;
	}
}
