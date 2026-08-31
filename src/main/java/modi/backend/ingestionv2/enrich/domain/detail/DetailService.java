package modi.backend.ingestionv2.enrich.domain.detail;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;
import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.Enrichment;
import modi.backend.ingestionv2.enrich.domain.EnrichmentRepository;
import modi.backend.support.error.CoreException;

/**
 * 상세 보강 스텝.
 *
 * <ul>
 *   <li>판정·외부 호출·반영을 각각 공개 메서드로 분리. 트랜잭션 경계가 메서드 경계와 일치</li>
 *   <li>반영은 루트 잠금 아래에서 원장·하위 전이·완료 판정·이벤트를 한 번에 확정</li>
 *   <li>장르 서비스와 개장 시간 서비스를 알지 못함</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DetailService {

	private final EnrichmentRepository enrichmentRepository;
	private final DetailLedgerRepository detailLedgerRepository;
	private final CultureDetailClient detailClient;
	private final OutboxAppender outboxAppender;

	/** 판정. 원장에 이미 기록이 있으면 외부 호출이 필요 없다. */
	@Transactional(readOnly = true)
	public boolean alreadyFetched(String vendorKey) {
		return detailLedgerRepository.existsByVendorKey(vendorKey);
	}

	/** 외부 호출. 트랜잭션 밖에서 실행된다. */
	public DetailData fetch(String vendorKey) {
		return detailClient.fetchDetail(vendorKey);
	}

	/**
	 * 반영. 네 가지를 하나의 트랜잭션으로 확정한다.
	 *
	 * <ol>
	 *   <li>루트 행 잠금</li>
	 *   <li>원장 기록</li>
	 *   <li>하위 상태 전이와 후속 스텝 열림 판정</li>
	 *   <li>완료 판정과 이벤트 적재</li>
	 * </ol>
	 */
	@Transactional
	public void apply(String vendorKey, DetailData data) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		// 판정 단계의 확인은 유료 호출을 아끼기 위한 것이고, 여기의 확인은 유일 제약 위반을 피하기 위한 것이다.
		if (!detailLedgerRepository.existsByVendorKey(vendorKey)) {
			detailLedgerRepository.save(CultureDetailSnapshot.create(vendorKey, data));
		}
		appendAfterDetail(vendorKey, enrichment);
	}

	/** 원장이 이미 있는 경우의 반영. 외부 호출 없이 상태 전이와 이벤트만 이어붙인다. */
	@Transactional
	public void applyAlreadyFetched(String vendorKey) {
		Enrichment enrichment = enrichmentRepository.findForUpdate(vendorKey)
				.orElseThrow(() -> new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FOUND));
		appendAfterDetail(vendorKey, enrichment);
	}

	/**
	 * 상세 완료 반영의 공통부.
	 *
	 * <ul>
	 *   <li>적재할 이벤트 목록을 서비스가 정하지 않고 루트가 돌려준 것을 그대로 씀 - 순서 지식이 복사되지 않게</li>
	 *   <li>상세 다음이 완료일 수 없다는 것을 서비스가 미리 알지 않도록 완료 판정도 그대로 물어봄</li>
	 * </ul>
	 */
	private void appendAfterDetail(String vendorKey, Enrichment enrichment) {
		List<EnrichStep> opened = enrichment.onDetailDone();
		boolean completed = enrichment.completeIfAllDone();
		enrichmentRepository.save(enrichment);
		for (EnrichStep step : opened) {
			outboxAppender.append(eventOf(step), vendorKey);
		}
		if (completed) {
			outboxAppender.append(IngestionEventType.ENRICHED, vendorKey);
		}
	}

	private static IngestionEventType eventOf(EnrichStep step) {
		return switch (step) {
			case DETAIL -> IngestionEventType.DETAIL_READY;
			case GENRE -> IngestionEventType.GENRE_READY;
			case HOURS -> IngestionEventType.HOURS_READY;
		};
	}
}
