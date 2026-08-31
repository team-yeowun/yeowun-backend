package modi.backend.ingestionv2.stage.domain;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;
import modi.backend.support.error.CoreException;

/**
 * 스테이징 유스케이스.
 *
 * <ul>
 *   <li>코어 접근은 계약 인터페이스 타입으로만 주입하며 구현 클래스명을 참조하지 않음</li>
 *   <li>완비 여부를 다시 판단하지 않음(점검이 이미 판단)</li>
 *   <li>상태 변경은 전부 Staging 의 메서드 호출로 수행하고 저장 지점을 코드에 남김</li>
 *   <li>재시도 상한은 설정이 진실이라 여기서 읽어 애그리거트에 넘김</li>
 *   <li>트랜잭션 경계는 이 클래스가 아니라 파사드가 소유</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StageService {

	private final StageRepository stageRepository;

	private final ExhibitionAssembler assembler;

	private final PlaceRegistrar placeRegistrar;

	private final ExhibitionRegistrar exhibitionRegistrar;

	private final PlaceHoursGateway placeHoursGateway;

	private final OutboxAppender outboxAppender;

	/** 재시도 상한을 읽는 유일한 자리. 애그리거트는 상한값을 갖지 않고 인자로 받는다. */
	private final IngestionProperties properties;

	/** 조립, 전시장 해소, 코어 등록, 개장 시간 반영, 상태 전이를 한 흐름으로 수행한다. */
	public StageResult.Staged stage(String vendorKey) {
		LocalDateTime now = IngestionClock.now();
		Staging staging = stageRepository.findByVendorKeyForUpdate(vendorKey)
				.orElseGet(() -> stageRepository.save(Staging.pending(vendorKey, now)));

		if (staging.isStaged()) {
			return StageResult.Staged.alreadyStaged(vendorKey, staging.getStagedExhibitionId());
		}
		if (staging.isAbandoned()) {
			return StageResult.Staged.abandoned(vendorKey);
		}

		ExhibitionRegistration registration = assembler.assemble(vendorKey);

		// 개장 시간은 전시가 아니라 전시장에 붙는 값이라 전시장 id 가 먼저 필요하다.
		PlaceRegistrar.Resolved place = placeRegistrar.resolveOrCreate(registration.placeName(),
				registration.region(), registration.sigungu(), registration.gpsX(), registration.gpsY());

		ExhibitionRegistrar.Registered registered = exhibitionRegistrar.register(registration, now);

		assembler.assembleHours(vendorKey)
				.ifPresent(hours -> placeHoursGateway.applyHours(place.exhibitionPlaceId(), hours.formatted(),
						hours.status(), PlaceHoursVendor.GOOGLE, now));

		staging.markStaged(registered.exhibitionId(), now);
		stageRepository.save(staging);
		outboxAppender.append(IngestionEventType.STAGED, vendorKey);

		return StageResult.Staged.registered(vendorKey, registered.exhibitionId());
	}

	/**
	 * 실패를 애그리거트에 기록하고 다음에 할 일을 알린다. 행이 없으면 만들어 기록한다.
	 *
	 * <ul>
	 *   <li>이미 종결된 건이면 애그리거트가 아무것도 바꾸지 않고 ALREADY_SETTLED 를 돌려줌</li>
	 *   <li>그 경우에도 save 를 부르는 것은 흐름을 하나로 유지하기 위함이며 바뀐 필드가 없어 UPDATE 가 나가지 않음</li>
	 * </ul>
	 */
	public StageFailureOutcome recordFailure(String vendorKey, String summary) {
		LocalDateTime now = IngestionClock.now();
		Staging staging = stageRepository.findByVendorKey(vendorKey)
				.orElseGet(() -> Staging.pending(vendorKey, now));
		StageFailureOutcome outcome = staging.recordFailure(summary, now, properties.maxAttempts());
		stageRepository.save(staging);
		return outcome;
	}

	/**
	 * 관리자 수동 재시도. 상한을 소진해 멈춘 건만 다시 대기 상태로 돌린다.
	 *
	 * <ul>
	 *   <li>여기서 하는 일은 상태 되돌리기까지이고 이벤트 재전달은 배달 계층의 몫</li>
	 *   <li>INSPECTED 는 점검이 소유한 사실이라 스테이징이 대신 발행하지 않음</li>
	 * </ul>
	 */
	public StageResult.Reopened reopen(String vendorKey) {
		Staging staging = stageRepository.findByVendorKey(vendorKey)
				.orElseThrow(() -> new CoreException(StageErrorCode.STAGING_NOT_FOUND));
		staging.reopen(IngestionClock.now());
		stageRepository.save(staging);
		return new StageResult.Reopened(vendorKey);
	}
}
