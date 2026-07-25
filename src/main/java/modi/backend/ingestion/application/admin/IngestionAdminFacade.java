package modi.backend.ingestion.application.admin;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.OutboxPublisher;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.progress.ExhibitionProgress;
import modi.backend.ingestion.domain.progress.ProgressStatus;
import modi.backend.ingestion.infra.audit.IngestionRunJpaRepository;
import modi.backend.ingestion.infra.outbox.OutboxMessageJpaRepository;
import modi.backend.ingestion.infra.progress.ExhibitionProgressJpaRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 수집 파이프라인 관리자 대시보드 파사드(조합자, 설계 D5) — 조회(요약·런·진행·아웃박스)와 <b>수동 재시도</b>를
 * 맡는다. 자동 치유는 없다 — 시도 소진(총 3회)으로 굳은 영구 실패는 여기서만 되살아난다.
 *
 * <p>대시보드성 페이지·집계 질의는 JpaRepository 직사용(슬라이스 내부 실용 — 포트는 파이프라인 조회만 갖는다).
 * 상태 변경은 전부 Entity 메서드({@code reopen}·{@code reactivate}) 경유이고 저장 지점이 코드에 보인다.
 */
@Service
@RequiredArgsConstructor
public class IngestionAdminFacade {

	private static final int MAX_PAGE_SIZE = 100;

	private final ExhibitionProgressJpaRepository progressRepository;
	private final OutboxMessageJpaRepository outboxRepository;
	private final IngestionRunJpaRepository runRepository;
	/** 재시도 시 스텝 이벤트 부활 통로(멱등·부활 로직 재사용 — 발행 컴포넌트). */
	private final OutboxPublisher publisher;
	/** 정리 배치 tx의 소유자(아웃박스 메커니즘) — 파사드는 루프만 돈다(배치당 tx 분리). */
	private final ExhibitionOutboxService outboxService;
	private final OutboxProperties outboxProperties;

	@Transactional(readOnly = true)
	public IngestionAdminResult.Summary summary() {
		return new IngestionAdminResult.Summary(
				progressRepository.countByStatus(ProgressStatus.PENDING),
				progressRepository.countByStatus(ProgressStatus.ENRICHING),
				progressRepository.countByStatus(ProgressStatus.COMPLETED),
				progressRepository.countByStatus(ProgressStatus.FAILED),
				outboxRepository.countByStatus(OutboxMessageStatus.PENDING),
				outboxRepository.countByStatus(OutboxMessageStatus.FAILED_RETRYABLE),
				outboxRepository.countByStatus(OutboxMessageStatus.SUCCEEDED),
				outboxRepository.countByStatus(OutboxMessageStatus.FAILED_PERMANENT));
	}

	@Transactional(readOnly = true)
	public IngestionAdminResult.Page<IngestionAdminResult.Run> runs(int page, int size) {
		Page<IngestionAdminResult.Run> result = runRepository
				.findAll(pageRequest(page, size, Sort.by(Sort.Direction.DESC, "id")))
				.map(IngestionAdminResult.Run::from);
		return toPage(result);
	}

	/** 진행 목록 — status가 null이면 전체(최신 갱신순). */
	@Transactional(readOnly = true)
	public IngestionAdminResult.Page<IngestionAdminResult.Progress> progress(ProgressStatus status, int page,
			int size) {
		PageRequest request = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
		Page<ExhibitionProgress> rows = status == null
				? progressRepository.findAll(request)
				: progressRepository.findAllByStatus(status, request);
		return toPage(rows.map(IngestionAdminResult.Progress::from));
	}

	/** 아웃박스 목록 — status가 null이면 전체(최신 갱신순). */
	@Transactional(readOnly = true)
	public IngestionAdminResult.Page<IngestionAdminResult.Outbox> outbox(OutboxMessageStatus status, int page,
			int size) {
		PageRequest request = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
		Page<OutboxMessage> rows = status == null
				? outboxRepository.findAll(request)
				: outboxRepository.findAllByStatus(status, request);
		return toPage(rows.map(IngestionAdminResult.Outbox::from));
	}

	/**
	 * 진행 단위 수동 재시도 — [FAILED 재개({@code reopen}) + 미해소 스텝 이벤트 부활]. FAILED가 아니어도
	 * 미해소 스텝 이벤트만 부활시킬 수 있다(잔존 메시지 유실 치유). COMPLETED는 재시도 대상이 아니다.
	 */
	@Transactional
	public IngestionAdminResult.Retried retryProgress(String externalId) {
		ExhibitionProgress progress = progressRepository.findByExternalId(externalId)
				.orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "진행 상태 없음: " + externalId));
		if (progress.getStatus() == ProgressStatus.COMPLETED) {
			throw new CoreException(ErrorType.INVALID_INPUT, "이미 승격된 전시는 재시도 대상이 아니다: " + externalId);
		}
		LocalDateTime now = LocalDateTime.now();
		boolean reopened = progress.getStatus() == ProgressStatus.FAILED;
		progress.reopen(now);
		progressRepository.save(progress);
		// 재개 후의 다음 스텝에 해당하는 이벤트를 부활시킨다 — 소비는 릴레이가 즉시(적재 알림) 소비한다.
		IngestionEventType event = switch (progress.nextStep()) {
			case FETCH_DETAIL -> IngestionEventType.DRAFT_STAGED;
			case CLASSIFY_GENRE -> IngestionEventType.DETAIL_FETCHED;
			case PROMOTE -> IngestionEventType.DRAFT_READY;
			case NONE -> null;
		};
		if (event != null) {
			publisher.enqueueOrReactivate(event, externalId, now);
		}
		return new IngestionAdminResult.Retried(externalId, reopened, event != null,
				progress.nextStep().name());
	}

	/**
	 * 아웃박스 메시지 단위 수동 재시도 — 종료(영구 실패 포함) 메시지를 부활시킨다(전시장 축 등 진행과 무관한 축용).
	 * 부활은 {@link OutboxPublisher#enqueueOrReactivate} 경유다 — 커밋 직후 적재 알림이 함께 발행돼 릴레이가 즉시 소비한다
	 * (직접 save로 되살리면 다음 12시간 폴링까지 잠든다).
	 */
	@Transactional
	public IngestionAdminResult.Retried retryOutbox(Long messageId) {
		OutboxMessage message = outboxRepository.findById(messageId)
				.orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "아웃박스 메시지 없음: " + messageId));
		publisher.enqueueOrReactivate(message.getMessageType(), message.getTargetKey(), LocalDateTime.now());
		return new IngestionAdminResult.Retried(message.getTargetKey(), false, true,
				message.getMessageType().name());
	}

	/**
	 * SUCCEEDED 주간 정리(설계 §9) — 보존 기간(기본 7일)을 넘긴 성공 행을 <b>소량 배치 반복</b>으로 삭제한다.
	 * 루프가 파사드에, 배치 트랜잭션이 서비스에 있는 이유: 배치당 tx를 분리해야 대량 삭제의 일시 악화
	 * (삭제 마크·통계 왜곡 — 100만 건 실험 실측)를 피한다. FAILED_PERMANENT는 보존(감사·수동 재시도 재료).
	 * 주간 삭제량은 수백 행 수준이라 OPTIMIZE는 불필요하다(퍼지가 자연 소화 — 실험 §4의 악화는 98.4만 건 일괄 케이스).
	 */
	public IngestionAdminResult.Purged purgeSucceeded(LocalDateTime now) {
		LocalDateTime cutoff = now.minusDays(outboxProperties.purgeRetentionDays());
		int total = 0;
		int deleted;
		do {
			deleted = outboxService.purgeSucceededBatch(cutoff);
			total += deleted;
		} while (deleted >= outboxProperties.purgeBatchSize());
		return new IngestionAdminResult.Purged(total, cutoff);
	}

	private static PageRequest pageRequest(int page, int size, Sort sort) {
		return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE), sort);
	}

	private static <T> IngestionAdminResult.Page<T> toPage(Page<T> page) {
		return new IngestionAdminResult.Page<>(List.copyOf(page.getContent()), page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}
}
