package modi.backend.ingestion.application.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursBackfill;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;

/**
 * 전시 아웃박스 유스케이스 조율 — enqueue(멱등)·선별·상태 전이만 맡는다. 실제 외부 작업(상세 조회·AI 분류·
 * 영업시간 호출)은 각 처리기(enricher)가 <b>트랜잭션 밖</b>에서 하고, 여기 상태 전이 메서드를 트랜잭션으로 호출한다.
 *
 * <p><b>원자성(ADR-10)</b>: enqueue 메서드들은 {@code REQUIRED} 전파라, 상태 변경 트랜잭션 안에서 불리면 그
 * 트랜잭션에 합류한다 — 전시 저장과 후속 메시지 기록이 같이 성공하거나 같이 실패한다. enqueue가 커밋되면
 * {@link OutboxEnqueued} 이벤트로 릴레이 드레인을 앞당긴다(글루 — 유실돼도 폴링이 줍는다).
 *
 * <p>상태 변경은 전부 {@link OutboxMessage} 메서드 안에서만 일어난다(Facade는 load·조율·save). 낙관락 충돌
 * ({@code OptimisticLockException})은 호출부(처리기)가 "다른 워커 선점"으로 보고 skip한다 — 여기서 삼키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionOutboxFacade {

	private final OutboxMessageRepository outboxMessageRepository;
	/** target_key(=exhibition_place.place_key, 정규화 이름 — ADR-07)로 정준 영업시간 상태를 본다(재검증 가드용, 코어 계약). */
	private final PlaceHoursBackfill placeHoursBackfill;
	private final OutboxProperties properties;
	/** 커밋 직후 릴레이 드레인을 앞당기는 글루 이벤트({@link OutboxEnqueued}) 발행자. */
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 메시지를 아웃박스에 넣는다(멱등 — 같은 (종류, 대상)의 행이 이미 있으면 no-op). 이미 종료된 일회성 메시지는
	 * 되살리지 않는다(상세·장르·최초조회는 한 대상당 한 번이면 족하다 — 재검증이 필요한 건 {@link #enqueueHoursRefresh}뿐이다).
	 */
	@Transactional
	public void enqueue(OutboxMessageType messageType, String targetKey, LocalDateTime now) {
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
	 * 풀린 창에 드레인된 EXHIBITION_READY(no-op 소비→SUCCEEDED), 실패 전이 후 draft 가시화 전 크래시,
	 * 수동 개입 뒤 재스테이징. 이 안전망이 그 행을 되살려 draft가 영구 침묵하지 않게 한다(치유 경로).
	 * 재승격 방지의 실제 가드는 메시지 비부활이 아니라 draft terminal 검사 + external_id UK다 — 부활은 안전하다.
	 */
	@Transactional
	public void enqueueOrReactivate(OutboxMessageType messageType, String targetKey, LocalDateTime now) {
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

	/** 여러 대상을 한꺼번에 멱등 enqueue한다(장르 스윕처럼 다건 등록에 쓴다). */
	@Transactional
	public void enqueueAll(OutboxMessageType messageType, Collection<String> targetKeys, LocalDateTime now) {
		for (String targetKey : targetKeys) {
			// 자기호출이라 enqueue의 프록시 속성은 안 탄다 — 이 메서드가 이미 같은 @Transactional 경계라 무해하다.
			enqueue(messageType, targetKey, now);
		}
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
		PlaceHoursBackfill.HoursSyncState state = placeHoursBackfill.findHoursSyncState(placeKey).orElse(null);
		if (state == null) {
			return; // 가드 1: 전시장이 없거나 최초 조회 전 장소 — 재검증 이벤트 대상이 아니다(최초 FETCH는 별도 경로).
		}
		if (isRecentlySynced(state.syncedAt(), now)) {
			return; // 가드 2: 최근에 확인한 장소는 최소 간격 안이라 건너뛴다.
		}
		outboxMessageRepository.findByMessageTypeAndTargetKey(OutboxMessageType.REFRESH_PLACE_HOURS, placeKey)
				.ifPresentOrElse(existing -> {
					existing.reactivate(now); // 종료됐던 메시지면 되살리고, 미종료면 no-op(이미 선별 대상).
					outboxMessageRepository.save(existing);
				}, () -> outboxMessageRepository.save(
						OutboxMessage.enqueue(OutboxMessageType.REFRESH_PLACE_HOURS, placeKey, now)));
		eventPublisher.publishEvent(new OutboxEnqueued(OutboxMessageType.REFRESH_PLACE_HOURS));
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
	public List<OutboxMessage> findDue(OutboxMessageType messageType, int limit, LocalDateTime now) {
		return outboxMessageRepository.findDue(messageType, now, limit);
	}

	/** 성공 전이 — 낙관락 충돌 시 예외를 전파한다(호출부가 skip). */
	@Transactional
	public void markSucceeded(OutboxMessage message, LocalDateTime now) {
		message.succeed(now);
		outboxMessageRepository.save(message);
	}

	/**
	 * 실패 전이(백오프·최대 초과 승격은 Entity가 정책으로 판단). 낙관락 충돌 시 예외 전파.
	 * 장르(CLASSIFY_GENRE)만 시도 소진 없는 정책을 쓴다 — AI 장애는 무기한 대기(ADR-11, 사용자 확정)이고,
	 * 소진 승격되면 draft가 영구 승격 불가로 굳기 때문이다.
	 */
	@Transactional
	public void markFailed(OutboxMessage message, OutboxFailureType failureType, String error, LocalDateTime now) {
		var policy = message.getMessageType() == OutboxMessageType.CLASSIFY_GENRE
				? properties.genreRetryPolicy() : properties.retryPolicy();
		message.recordFailure(failureType, error, policy, now);
		outboxMessageRepository.save(message);
	}
}
