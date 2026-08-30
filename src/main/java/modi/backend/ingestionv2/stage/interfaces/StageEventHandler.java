package modi.backend.ingestionv2.stage.interfaces;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.ingestionv2.stage.domain.StageFacade;
import modi.backend.support.error.CoreException;

/**
 * INSPECTED 수신 지점.
 *
 * <ul>
 *   <li>파사드만 호출하고 서비스를 직접 주입하지 않음</li>
 *   <li>실패를 도메인 상태에 먼저 남긴 뒤 예외를 배달 계층으로 되돌림</li>
 *   <li>상한 소진은 재시도 대상이 아니라 격리 대상이므로 다른 오류 코드로 구분해 전달</li>
 *   <li>기록 시점에 이미 종결된 건은 경합에서 진 것이므로 예외 없이 정상 반환</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StageEventHandler implements IngestionEventHandler {

	/** 실패 요약 길이. 원문 스택은 로그에 남기고 상태 컬럼에는 앞부분만 남긴다. */
	private static final int SUMMARY_LENGTH = 300;

	private final StageFacade stageFacade;

	@Override
	public boolean supports(IngestionEventType type) {
		return type == IngestionEventType.INSPECTED;
	}

	@Override
	public void handle(String vendorKey) {
		try {
			stageFacade.stage(vendorKey);
		} catch (RuntimeException failure) {
			switch (stageFacade.recordFailure(vendorKey, summarize(failure))) {
				// 다른 인스턴스가 이미 반영을 끝냈다. 이번 실패는 사실이 아니므로 항목을 걷어내게 한다.
				case ALREADY_SETTLED -> {
					return;
				}
				case EXHAUSTED -> throw new CoreException(IngestionErrorCode.RETRY_EXHAUSTED);
				case RETRYABLE -> throw failure;
			}
		}
	}

	private static String summarize(RuntimeException failure) {
		String summary = failure.getClass().getSimpleName() + ": " + failure.getMessage();
		return summary.length() <= SUMMARY_LENGTH ? summary : summary.substring(0, SUMMARY_LENGTH);
	}
}
