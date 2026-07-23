package modi.backend.ingestion.application.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.IngestionEventType;

/**
 * 전시 아웃박스 유스케이스 조율 — enqueue(멱등)·선별·상태 전이에 더해 <b>소비 수명주기</b>({@link #drain})까지
 * 맡는다. 실제 외부 작업(상세 조회·AI 분류·영업시간 호출)은 스텝 핸들러(파사드)가 <b>트랜잭션 밖</b>에서 하고,
 * 그 판정({@link StepResult})대로 여기가 상태 전이 메서드를 트랜잭션으로 호출한다.
 *
 * <p><b>원자성(ADR-10)</b>: enqueue 메서드들은 {@code REQUIRED} 전파라, 상태 변경 트랜잭션 안에서 불리면 그
 * 트랜잭션에 합류한다 — 전시 저장과 후속 메시지 기록이 같이 성공하거나 같이 실패한다. enqueue가 커밋되면
 * {@link OutboxEnqueued} 이벤트로 릴레이 드레인을 앞당긴다(글루 — 유실돼도 폴링이 줍는다).
 *
 * <p>상태 변경은 전부 {@link OutboxMessage} 메서드 안에서만 일어난다(Facade는 load·조율·save). 낙관락 충돌
 * ({@code OptimisticLockException})은 "다른 워커 선점"이다 — {@link #drain}이 흡수해 무전이 skip으로 처리한다
 * (스케줄러와 수동 트리거가 같은 메시지를 동시에 집으면 종료 전이 저장에서 한쪽만 이긴다). 개별 전이 메서드
 * ({@link #markSucceeded}/{@link #markFailed})는 예외를 그대로 전파한다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionOutboxService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionOutboxService.class);

	private final OutboxMessageRepository outboxMessageRepository;
	/** target_key(=exhibition_place.place_key, 정규화 이름 — ADR-07)로 정준 영업시간 상태를 본다(재검증 가드용, 코어 계약). */
	private final PlaceHoursGateway placeHoursGateway;
	private final OutboxProperties properties;
	/** 커밋 직후 릴레이 드레인을 앞당기는 글루 이벤트({@link OutboxEnqueued}) 발행자. */
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 메시지를 아웃박스에 넣는다(멱등 — 같은 (종류, 대상)의 행이 이미 있으면 no-op). 이미 종료된 일회성 메시지는
	 * 되살리지 않는다(상세·장르·최초조회는 한 대상당 한 번이면 족하다 — 재검증이 필요한 건 {@link #enqueueHoursRefresh}뿐이다).
	 */
	@Transactional
	public void enqueue(IngestionEventType messageType, String targetKey, LocalDateTime now) {
		if (targetKey == null || targetKey.isBlank()) {
			return;
		}
		if (outboxMessageRepository.findByMessageTypeAndTargetKey(messageType, targetKey).isPresent()) {
			return;
		}
		outboxMessageRepository.save(OutboxMessage.enqueue(messageType, targetKey, now));
		eventPublisher.publishEvent(new OutboxEnqueued(messageType));
	}

	/**
	 * 멱등 enqueue + <b>종료 메시지 부활</b> 변형 — 재sync 안전망 전용(ADR-12 보강). 일반 {@link #enqueue}는 종료된
	 * 일회성 메시지를 되살리지 않지만, "종료 메시지 + 미종료 draft" 조합이 실제로 생긴다: 게이트가 일시적으로
	 * 풀린 창에 드레인된 DRAFT_READY(no-op 소비→SUCCEEDED), 실패 전이 후 draft 가시화 전 크래시,
	 * 수동 개입 뒤 재스테이징. 이 안전망이 그 행을 되살려 draft가 영구 침묵하지 않게 한다(치유 경로).
	 * 재승격 방지의 실제 가드는 메시지 비부활이 아니라 draft terminal 검사 + external_id UK다 — 부활은 안전하다.
	 */
	@Transactional
	public void enqueueOrReactivate(IngestionEventType messageType, String targetKey, LocalDateTime now) {
		if (targetKey == null || targetKey.isBlank()) {
			return;
		}
		outboxMessageRepository.findByMessageTypeAndTargetKey(messageType, targetKey)
				.ifPresentOrElse(existing -> {
					existing.reactivate(now); // 종료됐던 메시지면 되살리고, 미종료면 no-op(이미 선별 대상).
					outboxMessageRepository.save(existing);
				}, () -> outboxMessageRepository.save(OutboxMessage.enqueue(messageType, targetKey, now)));
		eventPublisher.publishEvent(new OutboxEnqueued(messageType));
	}

	/**
	 * 이벤트 구동 영업시간 재검증 enqueue(설계 §4-1) — 새 전시가 <b>기존 장소</b>에 들어올 때 호출된다. 가드 2개:
	 * <ol>
	 *   <li><b>기존 장소만</b>: {@code place_hours} 행이 없으면(=최초 조회 전) 재검증 대상이 아니다(최초 FETCH는 별도 경로).</li>
	 *   <li><b>최소 간격</b>: {@code synced_at}이 설정 간격 이내면 skip — 한 번의 카탈로그 sync에 같은 장소로 전시가
	 *       쏟아져도 유료 호출이 burst하지 않게 한다.</li>
	 * </ol>
	 * UK 중복 방지는 {@link #enqueue}와 동일하나, 재검증은 <b>종료된 이전 메시지를 되살린다</b>(reactivate) — 재검증은 반복 이벤트다.
	 */
	@Transactional
	public void enqueueHoursRefresh(String placeKey, LocalDateTime now) {
		if (placeKey == null || placeKey.isBlank()) {
			return;
		}
		// target_key는 exhibition_place.place_key(정규화 이름)다 — 코어 계약으로 그 장소의 정준 영업시간 상태를 본다.
		PlaceHoursGateway.HoursSyncState state = placeHoursGateway.findHoursSyncState(placeKey).orElse(null);
		if (state == null) {
			return; // 가드 1: 전시장이 없거나 최초 조회 전 장소 — 재검증 이벤트 대상이 아니다(최초 FETCH는 별도 경로).
		}
		if (isRecentlySynced(state.syncedAt(), now)) {
			return; // 가드 2: 최근에 확인한 장소는 최소 간격 안이라 건너뛴다.
		}
		outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_HOURS_STALE, placeKey)
				.ifPresentOrElse(existing -> {
					existing.reactivate(now); // 종료됐던 메시지면 되살리고, 미종료면 no-op(이미 선별 대상).
					outboxMessageRepository.save(existing);
				}, () -> outboxMessageRepository.save(
						OutboxMessage.enqueue(IngestionEventType.PLACE_HOURS_STALE, placeKey, now)));
		eventPublisher.publishEvent(new OutboxEnqueued(IngestionEventType.PLACE_HOURS_STALE));
	}

	private boolean isRecentlySynced(LocalDateTime syncedAt, LocalDateTime now) {
		if (syncedAt == null) {
			return false; // 성공 확인 시각을 모르면 막지 않는다(재검증 허용).
		}
		LocalDateTime threshold = now.minusDays(properties.hoursRefreshMinIntervalDays());
		return syncedAt.isAfter(threshold);
	}

	/** 선별 — 도래한 메시지를 도래 순으로 최대 {@code limit}건 읽는다(외부 작업은 호출부가 트랜잭션 밖에서 수행). */
	@Transactional(readOnly = true)
	public List<OutboxMessage> findDue(IngestionEventType messageType, int limit, LocalDateTime now) {
		return outboxMessageRepository.findDue(messageType, now, limit);
	}

	/** 기본 배치 선별 — 배치 크기는 메커니즘 소유 정책({@code OutboxProperties})이라 호출부가 알 필요 없다. */
	@Transactional(readOnly = true)
	public List<OutboxMessage> findDueBatch(IngestionEventType messageType, LocalDateTime now) {
		return findDue(messageType, properties.batchSize(), now);
	}

	/** 성공 전이 — 낙관락 충돌 시 예외를 전파한다(호출부가 skip). */
	@Transactional
	public void markSucceeded(OutboxMessage message, LocalDateTime now) {
		message.succeed(now);
		outboxMessageRepository.save(message);
	}

	/**
	 * 실패 전이(백오프·최대 초과 승격은 Entity가 정책으로 판단). 낙관락 충돌 시 예외 전파.
	 * 장르 스텝의 이벤트(DETAIL_FETCHED 소비 = AI 분류)만 시도 소진 없는 정책을 쓴다 — AI 장애는 무기한 대기(ADR-11, 사용자 확정)이고,
	 * 소진 승격되면 draft가 영구 승격 불가로 굳기 때문이다.
	 */
	@Transactional
	public void markFailed(OutboxMessage message, OutboxFailureType failureType, String error, LocalDateTime now) {
		var policy = message.getMessageType() == IngestionEventType.DETAIL_FETCHED
				? properties.genreRetryPolicy() : properties.retryPolicy();
		message.recordFailure(failureType, error, policy, now);
		outboxMessageRepository.save(message);
	}

	// ──────────────────────────────────────────────────────────────────────────
	// 소비 수명주기(drain) — 선별→스텝 실행→판정대로 전이. 메커니즘의 책임이지 파사드의 책임이 아니다.
	// ──────────────────────────────────────────────────────────────────────────

	/** 기본 배치 크기로 1배치 드레인 — 배치 크기는 메커니즘 소유 정책({@code OutboxProperties.batchSize})이다. */
	public void drain(IngestionEventType messageType, Function<OutboxMessage, StepResult> step) {
		drain(messageType, properties.batchSize(), 1, step, null);
	}

	/**
	 * 영구 실패 콜백 드레인 — 전이 후 메시지가 {@code FAILED_PERMANENT}로 굳었으면 {@code onPermanentFailure}를
	 * 부른다. 상세·승격의 "필수 스텝 영구 실패 → draft FAILED 가시화" 연동은 흐름 정책이라 파사드가 콜백으로 준다.
	 */
	public void drain(IngestionEventType messageType, Function<OutboxMessage, StepResult> step,
			Consumer<OutboxMessage> onPermanentFailure) {
		drain(messageType, properties.batchSize(), 1, step, onPermanentFailure);
	}

	/**
	 * 다중 배치 드레인 — 도래분이 마를 때까지 최대 {@code maxBatches}회 반복한다(실행당 처리 상한 =
	 * {@code batchSize × maxBatches}). 장르처럼 배치 크기·반복 상한이 그 축의 유량 정책일 때 값을 넘긴다.
	 * 반복은 큐를 훑는 방식이라 메커니즘의 몫이다 — 호출부가 루프를 들지 않는다.
	 */
	public void drain(IngestionEventType messageType, int batchSize, int maxBatches,
			Function<OutboxMessage, StepResult> step) {
		drain(messageType, batchSize, maxBatches, step, null);
	}

	/**
	 * 소비 수명주기 템플릿 — [도래 메시지 선별 → 메시지마다 스텝 실행(tx 밖) → {@link StepResult}대로 전이]를
	 * 도래분이 마르거나 {@code maxBatches}에 닿을 때까지 반복한다. 전이 저장의 낙관락 충돌은 다른 워커 선점 =
	 * 무전이 skip으로 흡수한다.
	 *
	 * <p><b>처리 건수는 밖으로 내보내지 않는다</b> — 운영이 알고 싶은 "몇 건 처리했나"는 여기서 로그로 답하고
	 * ({@link IngestionEventType#stepLabel()}이 스텝 이름을 안다), 결과는 메시지 상태로 테이블에 남는다.
	 * 호출부가 세어 나르면 그 수를 다시 합치는 카운터가 위층마다 생긴다.
	 */
	private void drain(IngestionEventType messageType, int batchSize, int maxBatches,
			Function<OutboxMessage, StepResult> step, Consumer<OutboxMessage> onPermanentFailure) {
		int transitioned = 0;
		for (int batch = 0; batch < maxBatches; batch++) {
			LocalDateTime now = LocalDateTime.now();
			List<OutboxMessage> messages = findDue(messageType, batchSize, now);
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
			log.info("{} 이벤트 처리 {}건", messageType.stepLabel(), transitioned);
		}
	}

	/** 판정 한 건을 전이로 옮긴다. @return true면 전이함, false면 skip(판정 자체가 skip이거나 낙관락 선점). */
	/**
	 * 스텝 1건 실행 — <b>스텝이 던진 예외를 여기서 판정으로 번역한다</b>. 스텝(오케스트레이터)은 서비스 호출을
	 * 조립만 하고 try/catch를 들지 않는다: 예외의 의미(선점인가·재시도인가·영구 실패인가)는 메시지 수명주기의
	 * 어휘라 이 메커니즘이 알아야 할 것이고, 4개 스텝에 같은 catch를 복붙하면 규칙이 흩어진다.
	 */
	private StepResult runStep(Function<OutboxMessage, StepResult> step, OutboxMessage message) {
		try {
			return step.apply(message);
		} catch (OptimisticLockingFailureException e) {
			return StepResult.skip(); // 반영 중 충돌 — 다른 워커가 선점했다(무전이).
		} catch (RuntimeException e) {
			return StepResult.fail(failureTypeOf(message.getMessageType(), e), OutboxFailures.describe(e));
		}
	}

	/**
	 * 이벤트 종류별 실패 해석. 장르 스텝(DETAIL_FETCHED 소비 = AI 분류)만 <b>무조건 RETRYABLE</b>이다 —
	 * AI 장애는 무기한 대기가 정책이고(ADR-11), 여기서 PERMANENT로 굳히면 cause 체인의 IllegalArgumentException
	 * 따위에 draft가 영구 미승격으로 조용히 숨는다(상세와 달리 장르엔 draft FAILED 연동이 없다).
	 * {@link #markFailed}가 같은 종류에 시도 소진 없는 재시도 정책을 쓰는 것과 짝이다.
	 */
	private OutboxFailureType failureTypeOf(IngestionEventType messageType, RuntimeException e) {
		return messageType == IngestionEventType.DETAIL_FETCHED
				? OutboxFailureType.RETRYABLE : OutboxFailures.classify(e);
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
