package modi.backend.ingestionv2.enrich.interfaces;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.ingestionv2.enrich.domain.FailureOutcome;
import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifyFailedException;
import modi.backend.support.error.CoreException;

/**
 * 장르 보강 실행 핸들러.
 *
 * <ul>
 *   <li>전 공급자 소진이 예외가 아니라 결과값이라 공통부를 쓰지 않고 직접 번역</li>
 *   <li>실패 기록에 폴백 여부와 실패한 공급자 목록이 함께 남음</li>
 *   <li>이미 종결된 스텝이면 정상 반환. 끝난 건이 회수 주기마다 되살아나지 않게 함</li>
 *   <li>분류 실패가 아닌 예외는 잡지 않음. 기록 없이 미처리로 남아 다시 전달됨</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class GenreReadyEventHandler implements IngestionEventHandler {

	private final EnrichFacade enrichFacade;

	@Override
	public boolean supports(IngestionEventType type) {
		return type == IngestionEventType.GENRE_READY;
	}

	@Override
	public void handle(String vendorKey) {
		try {
			enrichFacade.enrichGenre(vendorKey);
		} catch (GenreClassifyFailedException failure) {
			FailureOutcome outcome = enrichFacade.recordGenreFailure(vendorKey, failure.result());
			switch (outcome) {
				case ALREADY_DONE -> {
					return;
				}
				case EXHAUSTED -> throw new CoreException(IngestionErrorCode.RETRY_EXHAUSTED);
				case RETRY -> throw failure;
			}
		}
	}
}
