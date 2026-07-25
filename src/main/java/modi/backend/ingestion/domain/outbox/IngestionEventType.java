package modi.backend.ingestion.domain.outbox;

/**
 * 전시 아웃박스({@link OutboxMessage})가 나르는 <b>이벤트</b>의 종류 — "내가 한 일"(발행자의 사실)이지
 * "다음에 할 일"(커맨드)이 아니다(설계 §1). 발행자는 전부 진행 상태 서비스({@code ExhibitionProgressService})
 * 하나다 — 4종 모두 진행 상태 변경(스테이징·스텝 해소·게이트)에 붙는 사실이기 때문이다(설계 §4).
 * 이벤트를 소비해 다음 스텝을 실행하는 매핑은 {@code ExhibitionIngestionOrchestrator} 한 곳에만 있다.
 *
 * <p>{@code target_key}의 의미가 종류마다 다르다: 전시 축은 원천키({@code external_id}), 전시장 축은
 * 장소키({@code place_key})다. 두 키 공간이 UK{@code (message_type, target_key)} 안에서 종류로 분리되므로 충돌하지 않는다.
 *
 * <p>영업시간 재검증({@code PLACE_HOURS_STALE})은 폐기됐다(설계 D4) — 영업시간은 낡아도 되는 정책이고,
 * 조회는 전시장 최초 초기화(PLACE_STAGED 소비) 1회뿐이다.
 */
public enum IngestionEventType {

	/**
	 * 목록이 스테이징됐다 — {@code target_key = external_id}. 발행: 스테이징 tx.
	 * 소비 시 다음 스텝: 상세(detail2) 조회 3박자.
	 */
	DRAFT_STAGED("상세"),

	/**
	 * 전시장 할 일이 발견됐다 — {@code target_key = place_key}(장소당 1건, UK dedup — 한 sync에 같은 장소
	 * 전시 10개여도 이벤트 1건 = 유료 구글 호출 장소당 1번). 발행: 스테이징 tx(DRAFT_STAGED와 같은 tx).
	 * 소비 시 다음 스텝: 전시장 초기화(resolve-or-create + 신규만 구글 조회).
	 */
	PLACE_STAGED("전시장 초기화"),

	/**
	 * 상세 스텝이 해소됐다(값 도착·무상세 확인) — {@code target_key = external_id}. 발행: 상세 반영 tx.
	 * 소비 시 다음 스텝: AI 장르 분류 3박자(Gemini→OpenAI 폴백은 분류기 체인 내부).
	 */
	DETAIL_FETCHED("전시 장르"),

	/**
	 * 승격 게이트를 채웠다 — {@code target_key = external_id}. 게이트를 채운 마지막 스텝 트랜잭션이
	 * <b>원자 기록</b>하고, 소비 측이 원장 3종을 어셈블해 코어 등록 계약({@code ExhibitionRegistrar})을
	 * 호출한다(payload 없음 — 재조회 방식이라 스키마 불변). 등록은 {@code exhibitions.external_id} UK로 멱등.
	 */
	DRAFT_READY("승격");

	/** 이 이벤트를 소비하면 무슨 스텝인가 — 운영 로그의 어휘다. */
	private final String stepLabel;

	IngestionEventType(String stepLabel) {
		this.stepLabel = stepLabel;
	}

	public String stepLabel() {
		return stepLabel;
	}
}
