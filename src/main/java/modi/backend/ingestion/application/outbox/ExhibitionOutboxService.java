package modi.backend.ingestion.application.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.properties.OutboxProperties;

/**
 * 아웃박스 <b>소비 메커니즘</b> — 선별·상태 전이·소비 수명주기({@link #consume})를 맡는다(설계 §3-5).
 * 발행은 {@link OutboxPublisher} 컴포넌트로 분리됐다(의존 규칙 §1-1 — 이 서비스는 오케스트레이터만 호출한다).
 * 실제 외부 작업(상세 조회·AI 분류·구글 조회)은 스텝 핸들러(오케스트레이터)가 <b>트랜잭션 밖</b>에서 하고,
 * 그 판정({@link StepResult})대로 여기가 상태 전이 메서드를 트랜잭션으로 호출한다.
 *
 * <p><b>재시도 정책은 하나다(설계 D5)</b>: 총 시도 {@code maxAttempts}(기본 3 = 최초 1 + 재시도 2), 소진 시
 * FAILED_PERMANENT — 이후 자동 재시도 없음(관리자 수동 재시도만). 구 장르 무기한 특례(ADR-11)는 폐지됐다 —
 * 어느 스텝이든 영구 실패는 {@code onPermanentFailure} 콜백으로 진행 상태 FAILED 가시화와 짝이 된다.
 *
 * <p>상태 변경은 전부 {@link OutboxMessage} 메서드 안에서만 일어난다. 낙관락 충돌은 "다른 워커 선점"이다 —
 * {@link #consume}이 흡수해 무전이 skip으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionOutboxService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionOutboxService.class);

	private final OutboxMessageRepository outboxMessageRepository;
	private final OutboxProperties properties;

	/** 선별 — 도래한 메시지를 도래 순으로 최대 {@code limit}건 읽는다(외부 작업은 호출부가 트랜잭션 밖에서 수행). */
	@Transactional(readOnly = true)
	public List<OutboxMessage> findDue(IngestionEventType eventType, int limit, LocalDateTime now) {
		return outboxMessageRepository.findDue(eventType, now, limit);
	}

	/** 성공 전이 — 낙관락 충돌 시 예외를 전파한다(호출부가 skip). */
	@Transactional
	public void markSucceeded(OutboxMessage message, LocalDateTime now) {
		message.succeed(now);
		outboxMessageRepository.save(message);
	}

	/** 실패 전이(백오프·최대 초과 승격은 Entity가 정책으로 판단 — 정책은 전 스텝 단일, D5). 낙관락 충돌 시 예외 전파. */
	@Transactional
	public void markFailed(OutboxMessage message, modi.backend.ingestion.domain.outbox.OutboxFailureType failureType,
			String error, LocalDateTime now) {
		message.recordFailure(failureType, error, properties.retryPolicy(), now);
		outboxMessageRepository.save(message);
	}

	/**
	 * SUCCEEDED 정리 1배치(설계 §9 — 주간 정리) — 보존 기간을 넘긴 성공 행을 소량 삭제하고 삭제 수를 돌려준다.
	 * <b>배치당 트랜잭션</b>이다: 100만 건 실험에서 대량 일괄 DELETE가 삭제 마크·통계 왜곡으로 일시 악화(30배)를
	 * 만드는 걸 실측했다 — 반복 호출(루프)은 조합자가, 한 입 크기는 메커니즘 설정이 정한다.
	 * FAILED_PERMANENT는 대상이 아니다(관리자 감사·수동 재시도 재료).
	 */
	@Transactional
	public int purgeSucceededBatch(LocalDateTime cutoff) {
		return outboxMessageRepository.purgeSucceededBefore(cutoff, properties.purgeBatchSize());
	}

	// ──────────────────────────────────────────────────────────────────────────
	// 소비 수명주기(consume) — 선별→스텝 실행→판정대로 전이. 메커니즘의 책임이지 조합자의 책임이 아니다.
	// ──────────────────────────────────────────────────────────────────────────

	/** 기본 배치 크기로 1배치 소비 — 배치 크기는 메커니즘 소유 정책({@code OutboxProperties.batchSize})이다. */
	public void consume(IngestionEventType eventType, Function<OutboxMessage, StepResult> step) {
		consume(eventType, properties.batchSize(), 1, step, null);
	}

	/**
	 * 영구 실패 콜백 소비 — 전이 후 메시지가 {@code FAILED_PERMANENT}로 굳었으면 {@code onPermanentFailure}를
	 * 부른다. "필수 스텝 영구 실패 → 진행 상태 FAILED 가시화" 연동은 흐름 정책이라 조합자가 콜백으로 준다.
	 */
	public void consume(IngestionEventType eventType, Function<OutboxMessage, StepResult> step,
			Consumer<OutboxMessage> onPermanentFailure) {
		consume(eventType, properties.batchSize(), 1, step, onPermanentFailure);
	}

	/**
	 * 다중 배치 소비 — 도래분이 마를 때까지 최대 {@code maxBatches}회 반복한다(실행당 처리 상한 =
	 * {@code batchSize × maxBatches}). 장르처럼 배치 크기·반복 상한이 그 축의 유량 정책일 때 값을 넘긴다.
	 */
	public void consume(IngestionEventType eventType, int batchSize, int maxBatches,
			Function<OutboxMessage, StepResult> step, Consumer<OutboxMessage> onPermanentFailure) {
		int transitioned = 0;
		for (int batch = 0; batch < maxBatches; batch++) {
			LocalDateTime now = LocalDateTime.now();
			List<OutboxMessage> messages = findDue(eventType, batchSize, now);
			if (messages.isEmpty()) {
				break; // 도래 이벤트 소진 → 조기 종료
			}
			for (OutboxMessage message : messages) {
				if (transition(message, runStep(step, message), onPermanentFailure, now)) {
					transitioned++;
				}
			}
		}
		if (transitioned > 0) {
			log.info("{} 이벤트 처리 {}건", eventType.stepLabel(), transitioned);
		}
	}

	/**
	 * 스텝 1건 실행 — <b>스텝이 던진 예외를 여기서 판정으로 번역한다</b>. 스텝(오케스트레이터)은 서비스 호출을
	 * 조립만 하고 try/catch를 들지 않는다: 예외의 의미(선점인가·재시도인가·영구 실패인가)는 메시지 수명주기의
	 * 어휘라 이 메커니즘이 알아야 할 것이고, 스텝마다 같은 catch를 복붙하면 규칙이 흩어진다.
	 */
	private StepResult runStep(Function<OutboxMessage, StepResult> step, OutboxMessage message) {
		try {
			return step.apply(message);
		} catch (OptimisticLockingFailureException e) {
			return StepResult.skip(); // 반영 중 충돌 — 다른 워커가 선점했다(무전이).
		} catch (RuntimeException e) {
			return StepResult.fail(OutboxFailures.classify(e), OutboxFailures.describe(e));
		}
	}

	private boolean transition(OutboxMessage message, StepResult result, Consumer<OutboxMessage> onPermanentFailure,
			LocalDateTime now) {
		try {
			switch (result.outcome()) {
				case SKIP -> {
					return false;
				}
				case SUCCESS -> markSucceeded(message, now);
				case FAIL -> {
					markFailed(message, result.failureType(), result.error(), now);
					if (onPermanentFailure != null && message.getStatus() == OutboxMessageStatus.FAILED_PERMANENT) {
						onPermanentFailure.accept(message);
					}
				}
			}
			return true;
		} catch (OptimisticLockingFailureException e) {
			return false; // 다른 워커가 선점 — 정상 skip(그 워커가 마감한다).
		}
	}
}
