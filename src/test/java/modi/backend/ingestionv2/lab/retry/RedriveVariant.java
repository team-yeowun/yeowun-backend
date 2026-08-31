package modi.backend.ingestionv2.lab.retry;

/**
 * 재처리 동시성 실험의 변형 - 계획서 step-07 변형표 그대로.
 *
 * <ul>
 *   <li>V1~V3 는 tx 경계를 1-tx 로 통일해 <b>락 방식만</b> 변수로 남긴 셋 - 다른 것은 잠금 획득 한 줄뿐</li>
 *   <li>P0 는 프로덕션 파사드(3-tx·무락) - 현행 재현이자 tx 경계 대조</li>
 *   <li>P0 는 {@code @Version} 마이그레이션 <b>이전</b> 커밋에서만 잴 수 있다(Phase A)</li>
 *   <li>실험 범위 축소 - V0(lab 무락 1-tx)·P1(프로덕션 낙관 3-tx) 폐기</li>
 *   <li>{@code lock}·{@code txBoundary}·{@code location} 은 원시 파일 조건에 그대로 실린다 -
 *       결과 문장이 두 변수를 섞지 않게 하는 장치</li>
 * </ul>
 */
enum RedriveVariant {

	V1("낙관 @Version", "1-tx", "lab 하네스 + 프로덕션 엔티티", "커밋 시점 버전 불일치 → 즉시 충돌 예외"),
	V2("비관 PESSIMISTIC_WRITE", "1-tx", "lab", "잠금 해제까지 대기 후 상태 검사에서 거절"),
	V3("비관 FOR UPDATE SKIP LOCKED", "1-tx", "lab", "건너뛰어 빈 결과 → 대기 없이 즉시 거절"),
	P0("없음", "3-tx", "프로덕션 파사드", "그대로 진행(경합 창)");

	private final String lock;
	private final String txBoundary;
	private final String location;
	private final String onLockedRow;

	RedriveVariant(String lock, String txBoundary, String location, String onLockedRow) {
		this.lock = lock;
		this.txBoundary = txBoundary;
		this.location = location;
		this.onLockedRow = onLockedRow;
	}

	String lock() {
		return lock;
	}

	String txBoundary() {
		return txBoundary;
	}

	String location() {
		return location;
	}

	String onLockedRow() {
		return onLockedRow;
	}

	/** 프로덕션 파사드를 그대로 부르는 변형인지 - 1-tx 하네스를 타지 않는다. */
	boolean usesProductionFacade() {
		return this == P0;
	}

	/**
	 * 이 변형이 속한 Phase - 측정 창을 가르는 값.
	 *
	 * <ul>
	 *   <li>A: {@code @Version} 마이그레이션 <b>이전</b> 커밋에서만 성립(P0)</li>
	 *   <li>B: 마이그레이션 직후에 붙여서 잰다 - 사이에 다른 프로덕션 변경을 넣지 않는다</li>
	 * </ul>
	 */
	String phase() {
		return this == P0 ? "A" : "B";
	}
}
