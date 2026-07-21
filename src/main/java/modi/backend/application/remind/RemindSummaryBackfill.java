package modi.backend.application.remind;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import modi.backend.domain.record.Record;
import modi.backend.domain.record.RecordEmotion;
import modi.backend.domain.remind.Remind;
import modi.backend.domain.remind.RemindAiStatus;
import modi.backend.domain.remind.RemindEmotion;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;
import modi.backend.support.time.AppTime;

/**
 * 리마인드 감정 변화 AI 요약을 저장 커밋 이후 백그라운드에서 생성·반영한다(응답 지연·서블릿 워커 점유 제거 — M-2).
 * 저장 시점엔 상태를 PENDING으로 두고, {@code aiExecutor} 스레드에서 요약(느린 LLM 호출)을 만든 뒤 짧은 tx로 Remind를 갱신한다.
 * best-effort — 실패/미설정/rate-limit이면 요약 없이 상태만 FAILED/SKIPPED로 남고, 본 저장/응답에는 영향이 없다.
 * ({@code RemindFacade.save}는 비트랜잭션이라 {@code remindRepository.save}가 이미 커밋된 뒤 호출된다 → afterCommit 훅 불필요.)
 * <p>
 * 백그라운드 작업은 인메모리라 <b>서버 재시작 시 유실</b>될 수 있어 PENDING이 영구히 남을 수 있다 —
 * {@link #backfillPending}이 이를 주기적으로 복구한다(스케줄 트리거는 {@code RemindSummaryBackfillScheduler}).
 */
@Component
public class RemindSummaryBackfill {

	private static final Logger log = LoggerFactory.getLogger(RemindSummaryBackfill.class);

	private final RemindAiSummarizer summarizer;
	private final RemindJpaRepository remindRepository;
	private final RecordJpaRepository recordRepository;
	private final Executor aiExecutor;
	private final TransactionTemplate transactionTemplate;

	public RemindSummaryBackfill(RemindAiSummarizer summarizer, RemindJpaRepository remindRepository,
			RecordJpaRepository recordRepository, Executor aiExecutor, PlatformTransactionManager transactionManager) {
		this.summarizer = summarizer;
		this.remindRepository = remindRepository;
		this.recordRepository = recordRepository;
		this.aiExecutor = aiExecutor;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/** 커밋된 Remind(remindId)의 감정 변화 요약을 백그라운드에서 생성해 반영한다. */
	public void schedule(Long remindId, RemindAiSummarizer.Context context) {
		aiExecutor.execute(() -> generateAndApply(remindId, context));
	}

	/**
	 * PENDING으로 남은 리마인드의 요약을 다시 생성한다(재시작 등으로 백그라운드 작업이 유실된 경우 복구).
	 * <ul>
	 *   <li>{@code grace}보다 오래된 건만 대상 — 지금 진행 중인 백그라운드 작업과 중복 호출하지 않는다.</li>
	 *   <li>{@code giveUpAfter}를 넘긴 건은 AI 호출 없이 FAILED로 확정 — 영구 PENDING(=클라 무한 폴링)을 없앤다.</li>
	 *   <li>{@code batchSize}로 1회 실행의 유료 AI 호출량을 제한한다.</li>
	 *   <li>AI가 설정돼 있는데 SKIPPED가 나오면 쿨다운이므로 PENDING을 유지해 다음 주기에 재시도한다.</li>
	 * </ul>
	 *
	 * @return 이번 실행에서 요약을 채운(READY/FAILED 확정) 건수
	 */
	public int backfillPending(int batchSize, Duration grace, Duration giveUpAfter) {
		if (batchSize <= 0 || !summarizer.isEnabled()) {
			// AI 미설정이면 저장 시점에 이미 SKIPPED로 확정되므로 PENDING이 쌓이지 않는다 — 호출할 것도 없다.
			return 0;
		}
		ZonedDateTime now = ZonedDateTime.now(AppTime.KST);
		List<PendingTarget> targets = loadTargets(now.minus(grace), batchSize);
		int resolved = 0;
		for (PendingTarget target : targets) {
			if (target.isExpired(now, giveUpAfter)) {
				apply(target.remindId(), new RemindAiSummarizer.Result(RemindAiStatus.FAILED, null));
				log.warn("리마인드 요약 백필 포기(PENDING 유지 시간 초과) remindId={}", target.remindId());
				resolved++;
				continue;
			}
			RemindAiSummarizer.Result result = summarizer.summarize(target.context());
			if (result.status() == RemindAiStatus.SKIPPED) {
				continue; // AI는 켜져 있으므로 쿨다운 — PENDING 유지하고 다음 주기에 재시도
			}
			apply(target.remindId(), result);
			resolved++;
		}
		return resolved;
	}

	private void generateAndApply(Long remindId, RemindAiSummarizer.Context context) {
		try {
			RemindAiSummarizer.Result result = summarizer.summarize(context); // 느린 LLM 호출 — DB tx 밖
			apply(remindId, result);
		} catch (RuntimeException e) {
			log.warn("리마인드 감정 변화 요약 백필 실패 remindId={}: {}", remindId, e.getMessage());
		}
	}

	/** 요약 결과를 짧은 tx로 반영한다(삭제됐으면 무시). */
	private void apply(Long remindId, RemindAiSummarizer.Result result) {
		transactionTemplate.executeWithoutResult(status ->
				remindRepository.findByIdAndDeletedAtIsNull(remindId)
						.ifPresent(remind -> remind.applyAiSummary(result.status(), result.summary())));
	}

	/** 대상 PENDING 리마인드를 읽어 요약 입력(Context)까지 만들어 둔다 — 지연 로딩 때문에 트랜잭션 안에서 수행. */
	private List<PendingTarget> loadTargets(ZonedDateTime createdBefore, int batchSize) {
		List<PendingTarget> targets = transactionTemplate.execute(status ->
				remindRepository.findPendingOlderThan(RemindAiStatus.PENDING, createdBefore,
								PageRequest.of(0, batchSize))
						.stream()
						.map(this::toTarget)
						.toList());
		return targets == null ? List.of() : targets;
	}

	private PendingTarget toTarget(Remind remind) {
		List<String> afterEmotions = remind.getEmotions().stream().map(RemindEmotion::getEmotionCode).toList();
		// 원본 기록은 삭제됐을 수 있다 — 그때의 감상/감정 없이도 '지금'만으로 요약을 시도한다.
		Record record = recordRepository.findByIdWithEmotions(remind.getRecordId()).orElse(null);
		String originalContent = record == null ? null : record.getContent();
		List<String> beforeEmotions = record == null ? List.of()
				: record.getEmotions().stream().map(RecordEmotion::getEmotionCode).toList();
		return new PendingTarget(remind.getId(), remind.getCreatedAt(),
				new RemindAiSummarizer.Context(remind.getUserId(), remind.getRecordId(), remind.getExhibitionTitle(),
						originalContent, beforeEmotions, remind.getReflection(), afterEmotions));
	}

	/** 백필 대상 1건 — 식별자 + 생성 시각(포기 판정용) + 요약 입력. */
	private record PendingTarget(Long remindId, ZonedDateTime createdAt, RemindAiSummarizer.Context context) {

		/** createdAt이 없으면(영속 전 등) 포기하지 않고 정상 시도한다. */
		boolean isExpired(ZonedDateTime now, Duration giveUpAfter) {
			return createdAt != null && createdAt.isBefore(now.minus(giveUpAfter));
		}
	}
}
