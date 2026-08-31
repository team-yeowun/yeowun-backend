package modi.backend.ingestionv2.enrich.domain;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;
import modi.backend.support.error.CoreException;

/**
 * 보강 루트의 생애 관리.
 *
 * <ul>
 *   <li>시작과 실패 기록처럼 특정 스텝에 속하지 않는 루트 수준의 일을 담당</li>
 *   <li>하위 서비스 셋과 형제 관계이며 서로를 호출하지 않음</li>
 *   <li>재시도 상한을 설정에서 읽어 엔티티에 넘기는 유일한 자리</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EnrichmentService {

	private final EnrichmentRepository enrichmentRepository;
	private final OutboxAppender outboxAppender;
	private final IngestionProperties properties;

	/**
	 * 보강 시작. 이미 있으면 아무 일도 하지 않는다.
	 *
	 * <ul>
	 *   <li>수집 완료 이벤트가 두 번 도착해도 애그리거트가 두 번 만들어지지 않음</li>
	 *   <li>생성과 상세 요청 이벤트 적재가 한 트랜잭션. 행은 있는데 아무도 실행하지 않는 상태를 만들지 않음</li>
	 * </ul>
	 */
	@Transactional
	public void start(String vendorKey) {
		if (enrichmentRepository.existsByVendorKey(vendorKey)) {
			return;
		}
		Enrichment enrichment = Enrichment.start(vendorKey);
		enrichmentRepository.save(enrichment);
		outboxAppender.append(IngestionEventType.DETAIL_READY, vendorKey);
	}

	/**
	 * 시도 실패 기록. 상한에 도달했으면 그 스텝과 루트를 실패로 확정한다.
	 *
	 * <ul>
	 *   <li>재시도 상한 판정의 근거는 도메인이 가진 시도 횟수. 배달 계층의 전달 횟수를 쓰지 않음</li>
	 *   <li>상한 값은 설정에서 읽어 넘김. 엔티티에 상수를 두지 않아 값이 갈라지지 않음</li>
	 *   <li>판정 결과를 돌려주어 호출부가 확인 처리와 재전달과 격리 중 무엇을 할지 정하게 함</li>
	 * </ul>
	 */
	@Transactional
	public FailureOutcome recordFailure(EnrichStep step, String vendorKey, String vendor, String error) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		FailureOutcome outcome = enrichment.recordFailure(step, vendor, error, properties.maxAttempts());
		enrichmentRepository.save(enrichment);
		return outcome;
	}

	/** 장르 실패 기록. 폴백까지 갔는지를 함께 남기는 점만 다르다. */
	@Transactional
	public FailureOutcome recordGenreFailure(String vendorKey, String vendor, String error, boolean fallbackUsed) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		FailureOutcome outcome = enrichment.recordGenreFailure(vendor, error, fallbackUsed, properties.maxAttempts());
		enrichmentRepository.save(enrichment);
		return outcome;
	}

	/**
	 * 관리자 수동 재시도.
	 *
	 * <ul>
	 *   <li>되돌리기와 실행 이벤트 적재가 한 트랜잭션. 상태만 열리고 아무도 부르지 않는 상태를 만들지 않음</li>
	 *   <li>잠금 조회로 여는 이유는 재시도 도중 도착한 재전달과 겹치지 않게 하기 위함</li>
	 * </ul>
	 */
	@Transactional
	public EnrichResult.Reopened reopen(String vendorKey) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		List<EnrichStep> reopened = enrichment.reopenFailedSteps();
		enrichmentRepository.save(enrichment);
		for (EnrichStep step : reopened) {
			outboxAppender.append(eventOf(step), vendorKey);
		}
		return new EnrichResult.Reopened(vendorKey, reopened);
	}

	/**
	 * 스텝을 실행 이벤트로 옮긴다.
	 *
	 * <ul>
	 *   <li>DetailService 에도 같은 매핑이 있다 - EnrichStep 이 배달 어휘를 알게 되면 관리자 응답 타입이
	 *       대기열 어휘를 참조하게 되므로 세 줄의 중복을 받아들였다</li>
	 * </ul>
	 */
	private static IngestionEventType eventOf(EnrichStep step) {
		return switch (step) {
			case DETAIL -> IngestionEventType.DETAIL_READY;
			case GENRE -> IngestionEventType.GENRE_READY;
			case HOURS -> IngestionEventType.HOURS_READY;
		};
	}
}
