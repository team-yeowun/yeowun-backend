package modi.backend.ingestionv2.stage.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 스테이징 진입점.
 *
 * <ul>
 *   <li>interfaces 계층은 이 파사드만 호출하고 서비스를 직접 주입하지 않음</li>
 *   <li>스테이징은 트랜잭션이 하나뿐이라 메서드 하나가 곧 트랜잭션 경계</li>
 *   <li>실패 기록은 본 트랜잭션이 롤백되어도 남아야 하므로 별도 트랜잭션</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StageFacade {

	private final StageService stageService;
	private final StageQueryService stageQueryService;

	/** 핵심 트랜잭션. 다섯 작업이 함께 커밋되거나 함께 롤백된다. */
	@Transactional
	public StageResult.Staged stage(String vendorKey) {
		return stageService.stage(vendorKey);
	}

	/**
	 * 실패 기록. 반영 트랜잭션과 운명을 함께하면 기록 자체가 사라지므로 새 트랜잭션에서 수행한다.
	 *
	 * <p>이 트랜잭션은 반영 트랜잭션의 행 잠금 밖에서 돈다. 다른 인스턴스가 이미 종결시킨 뒤에
	 * 이쪽 실패가 도착하는 경우를 막는 것은 잠금이 아니라 {@code Staging.recordFailure} 의 종결 가드다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public StageFailureOutcome recordFailure(String vendorKey, String summary) {
		return stageService.recordFailure(vendorKey, summary);
	}

	/** 관리자 수동 재시도. */
	@Transactional
	public StageResult.Reopened reopen(String vendorKey) {
		return stageService.reopen(vendorKey);
	}

	/** 관리자 실패 목록 조회. 쓰기 경로와 트랜잭션 성격이 달라 읽기 전용으로 연다. */
	@Transactional(readOnly = true)
	public StageResult.FailedPage findFailed(int page, int size) {
		return stageQueryService.findFailed(page, size);
	}
}
