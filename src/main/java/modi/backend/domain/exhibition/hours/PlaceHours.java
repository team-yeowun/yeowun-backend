package modi.backend.domain.exhibition.hours;

import modi.backend.domain.exhibition.catalog.ExhibitionPlace;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장소 영업시간(정준층 — 벤더 무관) — {@code place_hours} 매핑.
 *
 * <p><b>행 = 장소 1곳</b>(UK {@code place_key})이다. 전시가 아니라 장소의 속성이라, 같은 건물에서 열리는 전시 여럿이
 * 한 행을 공유한다 — 그래서 조회도 장소당 1콜이면 된다.
 *
 * <p>여기 들어가는 건 <b>우리가 정의한 표시 모델</b>({@code formatted})뿐이다. 벤더 원본 JSON은
 * {@link GooglePlaceSnapshot}에 남는다 — 정준층에 벤더 원본을 복사하면 Silver가 Bronze의 복제가 되어 층의 의미가 죽는다.
 * 변환 규칙(요일 묶기·휴무 표기)이 바뀌면 벤더 원본에서 이 값을 재생성한다.
 *
 * <p>{@code status}가 푸는 문제와 {@code provider}가 푸는 문제는 각각
 * {@link PlaceHoursStatus}·{@link PlaceHoursVendor} 참조.
 *
 * <p>{@link ExhibitionPlace}와는 {@code exhibition_place_id} 값으로만 이어진다(FK ❌, @ManyToOne ❌) — 정준층은 도메인과
 * 생명주기가 다르고 원본에서 재생성될 수 있는 층이다. 감사 컬럼이 필요 없어 {@code BaseEntity}를 상속하지 않는다.
 *
 * <p><b>이관 3단계에서 조인 키가 바뀌었다</b>: {@code place_key}(=주소) → {@code exhibition_place_id}. 전시장의 자연키가
 * 정규화 이름(ADR-07)으로 정해지면서 장소당 1행이 전시장 행에 직접 정렬된다. {@code synced_at}은 재검증 최소간격 판정과
 * 대상 선별의 기준 시각이다(부재 = 미보강).
 */
@Entity
@Table(name = "place_hours")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceHours {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "exhibition_place_id", nullable = false)
	private Long exhibitionPlaceId;

	/** 우리 표시 규칙 문자열({@link OpeningHoursFormatter} 산출). 영업시간 정보가 없으면 null. */
	@Column(name = "formatted", length = 500)
	private String formatted;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PlaceHoursStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 20)
	private PlaceHoursVendor provider;

	/** 우리가 센 조회 시도 횟수. 영구 실패 장소가 매 주기 무한 재시도되는 것을 막을 재료다. */
	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	/** 마지막 동기화 시각 — 대상 선별(미조회·만료)과 재검증 최소간격 판정의 기준(설계 §1·§3). */
	@Column(name = "synced_at")
	private LocalDateTime syncedAt;

	/**
	 * 다음 재시도 도래 시각. 백오프 정책은 큐(exhibition_outbox) 배선 단계에서 정한다 — 읽는 곳이 없는 지금 값을 지어내면,
	 * 나중에 그 값이 근거 있는 정책인 줄 알고 쓰게 된다.
	 */
	@Column(name = "next_attempt_at")
	private LocalDateTime nextAttemptAt;

	/** 조회 전용 센티넬 표식 — 영속 컬럼이 아니다. */
	@Transient
	private boolean sentinel;

	private PlaceHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor provider,
			LocalDateTime syncedAt) {
		this.exhibitionPlaceId = exhibitionPlaceId;
		this.formatted = formatted;
		this.status = status;
		this.provider = provider;
		this.attemptCount = 1;
		this.syncedAt = syncedAt;
	}

	/**
	 * <b>빈 영업시간</b>(조회 전용). 영업시간은 신규 전시장 1회 조회로 채워지므로 아직 없을 수 있다.
	 * 결측을 여기서 흡수해 호출부가 null을 확인하지 않게 한다. <b>저장할 수 없다</b>.
	 */
	public static PlaceHours empty() {
		PlaceHours hours = new PlaceHours();
		hours.sentinel = true;
		return hours;
	}

	/** 조회 결과로 장소 행을 처음 만든다. */
	public static PlaceHours first(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status,
			PlaceHoursVendor provider, LocalDateTime syncedAt) {
		return new PlaceHours(exhibitionPlaceId, formatted, status, provider, syncedAt);
	}

	/**
	 * 재조회 결과를 반영한다. 조회 결과 덮어쓰기가 정상 동작이다(영업시간은 바뀌는 값이고, 30일마다 갱신한다).
	 * <p>
	 * 다만 <b>실패로는 기존 표시값을 지우지 않는다</b>({@link PlaceHoursStatus#FAILED}) — 전송 오류 때문에
	 * 사용자에게 보이던 영업시간이 사라지면, 부가 기능의 일시 장애가 서비스 후퇴가 된다. 상태만 남기고 값은 지킨다.
	 * 반대로 NOT_FOUND·NO_HOURS는 <b>벤더가 "없다"고 답한 것</b>이라 값을 비우는 게 사실에 맞다.
	 */
	public void refresh(String formatted, PlaceHoursStatus status, PlaceHoursVendor provider, LocalDateTime syncedAt) {
		this.attemptCount++;
		this.status = status;
		this.provider = provider;
		this.syncedAt = syncedAt;
		if (status != PlaceHoursStatus.FAILED) {
			this.formatted = formatted;
		}
	}

	/**
	 * 조회가 전송 오류로 실패했음을 남긴다(재시도 대상). <b>표시값·동기화 시각은 보존한다</b> — 일시 장애로 사용자에게
	 * 보이던 영업시간이 사라지거나 재조회 대상에서 빠지면 부가 기능의 실패가 서비스 후퇴가 된다.
	 */
	public void recordFailure(PlaceHoursVendor provider) {
		this.attemptCount++;
		this.status = PlaceHoursStatus.FAILED;
		this.provider = provider;
	}

	/** mock으로 채워진 행인가 — 실호출 결과와 구분해 선별 재조회할 때 쓴다. */
	public boolean isMock() {
		return this.provider == PlaceHoursVendor.MOCK;
	}
	/** 빈 영업시간은 조회용이라 저장하면 유령 행이 된다 — 영속화 시점에 막는다. */
	@PrePersist
	@PreUpdate
	private void guardSentinel() {
		if (sentinel) {
			throw new IllegalStateException("빈 영업시간은 저장할 수 없습니다(조회 전용).");
		}
	}

}
