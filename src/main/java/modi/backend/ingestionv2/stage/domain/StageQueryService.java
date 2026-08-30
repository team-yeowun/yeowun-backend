package modi.backend.ingestionv2.stage.domain;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 조회 유스케이스.
 *
 * <ul>
 *   <li>페이지 크기 상한을 서비스가 강제해 한 번에 과도한 행을 읽지 않도록 함</li>
 *   <li>애그리거트를 출력 레코드로 옮기는 변환까지가 이 클래스의 책임</li>
 *   <li>StageService 와 서로 호출하지 않으며 조합은 파사드가 함</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StageQueryService {

	/** 한 번에 읽을 수 있는 최대 행 수. 관리자 화면이 큰 값을 보내도 여기서 잘린다. */
	private static final int MAX_PAGE_SIZE = 100;

	private final StageQueryRepository stageQueryRepository;

	/** 재시도 상한을 소진한 건을 마지막 실패 시각 내림차순으로 조회한다. */
	public StageResult.FailedPage findFailed(int page, int size) {
		int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int safePage = Math.max(page, 0);
		int offset = safePage * limit;
		List<StageResult.Failed> items = stageQueryRepository.findByStatus(StagingStatus.FAILED, offset, limit)
				.stream()
				.map(StageResult.Failed::from)
				.toList();
		return new StageResult.FailedPage(items, safePage, limit,
				stageQueryRepository.countByStatus(StagingStatus.FAILED));
	}
}
