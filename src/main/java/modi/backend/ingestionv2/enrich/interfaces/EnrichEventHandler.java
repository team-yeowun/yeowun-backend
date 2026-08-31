package modi.backend.ingestionv2.enrich.interfaces;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.FailureOutcome;
import modi.backend.support.error.CoreException;

/**
 * 보강 이벤트 핸들러의 공통부.
 *
 * <ul>
 *   <li>격벽의 가장자리에서 예외를 시도 횟수로 바꾸는 유일한 지점</li>
 *   <li>재시도 대상이면 원래 예외를 다시 던져 미처리로 남김</li>
 *   <li>상한 소진이면 RETRY_EXHAUSTED로 바꿔 던져 배달 계층이 격리하게 함</li>
 *   <li>이미 종결된 스텝이면 정상 반환. 끝난 일이 회수 주기마다 되살아나지 않게 함</li>
 *   <li>담당 스텝이 없으면 셀 자리가 없으므로 곧바로 상한 소진으로 취급</li>
 * </ul>
 */
@RequiredArgsConstructor
abstract class EnrichEventHandler implements IngestionEventHandler {

	/** last_error 컬럼에 남길 요약 길이. 원문 스택은 로그가 갖는다. */
	private static final int SUMMARY_LENGTH = 300;

	protected final EnrichFacade enrichFacade;

	/** 이 핸들러가 담당하는 이벤트. */
	protected abstract IngestionEventType eventType();

	/** 이 핸들러가 담당하는 스텝. 시작 핸들러는 담당 스텝이 없어 null. */
	protected abstract EnrichStep step();

	/** 실제 실행. 예외를 잡지 않고 그대로 던진다. */
	protected abstract void execute(String vendorKey);

	/** 외부 벤더 이름. 실패 기록에 남길 값. */
	protected abstract String vendorName();

	@Override
	public boolean supports(IngestionEventType type) {
		return eventType() == type;
	}

	@Override
	public void handle(String vendorKey) {
		try {
			execute(vendorKey);
		} catch (RuntimeException failure) {
			Optional<RuntimeException> translated = translate(vendorKey, failure);
			if (translated.isPresent()) {
				throw translated.get();
			}
		}
	}

	/** 실패를 도메인 사실로 번역한다. 비어 있는 값은 던질 것이 없다는 뜻이다. */
	private Optional<RuntimeException> translate(String vendorKey, RuntimeException failure) {
		if (step() == null) {
			// 세는 자리가 없는 상한은 실제로는 무한 재시도다. 보이지 않는 무한 재시도보다 보이는 격리가 낫다.
			return Optional.of(new CoreException(IngestionErrorCode.RETRY_EXHAUSTED));
		}
		FailureOutcome outcome = enrichFacade.recordFailure(step(), vendorKey, vendorName(), summarize(failure));
		return switch (outcome) {
			case ALREADY_DONE -> Optional.empty();
			case RETRY -> Optional.of(failure);
			case EXHAUSTED -> Optional.of(new CoreException(IngestionErrorCode.RETRY_EXHAUSTED));
		};
	}

	protected static String summarize(RuntimeException failure) {
		String summary = failure.getClass().getSimpleName() + ": " + failure.getMessage();
		return summary.length() <= SUMMARY_LENGTH ? summary : summary.substring(0, SUMMARY_LENGTH);
	}
}
