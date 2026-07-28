package modi.backend.domain.exhibition.catalog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 전시 상세(도메인 satellite) — {@code exhibition_detail} 매핑. 전시 고유의 <b>지연 도착</b> 정보를 담는다(ADR-03).
 *
 * <p><b>연관(행)의 부재 = 상세 미동기화</b>다. 그래서 {@code Exhibition} 코어에서 nullable을 걷어낼 수 있다 — 상세가
 * 아직 안 온 전시는 그냥 이 행이 없을 뿐이고, 코어는 여전히 완전하다(생성 시점 list 소스로 완결).
 * 행이 존재하면 {@code synced_at}은 반드시 있다(언제 동기화됐나). 값 필드(price/description/img_url)는 원천 결측이 잦아
 * nullable이며, "상세는 확인했으나 원천에 값이 없음"({@code markChecked})은 값 없이 {@code synced_at}만 있는 행으로 표현한다.
 *
 * <p>{@link Exhibition}과는 {@code exhibition_id}(UK)로 1:1 이어진다(satellite → 부모 실제 FK).
 */
@Entity
@Table(name = "exhibition_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExhibitionDetail {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "exhibition_id", nullable = false)
	private Long exhibitionId;

	@Column(name = "price", length = 500)
	private String price;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "img_url", length = 2048)
	private String imgUrl;

	@Column(name = "synced_at", nullable = false)
	private LocalDateTime syncedAt;

	/** 조회 전용 센티넬 표식 — 영속 컬럼이 아니다. */
	@Transient
	private boolean sentinel;

	private ExhibitionDetail(Long exhibitionId, String price, String description, String imgUrl,
			LocalDateTime syncedAt) {
		this.exhibitionId = exhibitionId;
		this.price = price;
		this.description = description;
		this.imgUrl = imgUrl;
		this.syncedAt = syncedAt;
	}

	/**
	 * <b>빈 상세</b>(조회 전용). 상세는 수집이 나중에 채우므로 <b>정말로 없을 수 있다</b>(ADR-03 — 부재는 연관의 부재).
	 * 그 결측을 여기서 한 번 흡수해, 호출부가 값마다 null을 확인하지 않게 한다.
	 * <b>저장할 수 없다</b> — 영속화 시점에 막는다.
	 */
	public static ExhibitionDetail empty() {
		ExhibitionDetail detail = new ExhibitionDetail();
		detail.sentinel = true;
		return detail;
	}

	/** 상세 값을 받아 행을 만든다(동기화 완료). */
	public static ExhibitionDetail create(Long exhibitionId, String price, String description, String imgUrl,
			LocalDateTime syncedAt) {
		return new ExhibitionDetail(exhibitionId, price, description, imgUrl, syncedAt);
	}

	/** 상세를 확인했으나 원천에 값이 없음 — 값 없이 동기화 시각만 남긴다(재조회 대상에서 빠지게). */
	public static ExhibitionDetail markChecked(Long exhibitionId, LocalDateTime syncedAt) {
		return new ExhibitionDetail(exhibitionId, null, null, null, syncedAt);
	}

	/** 상세 재동기화 결과를 반영한다(덮어쓰기 — 상세는 갱신될 수 있다). */
	public void update(String price, String description, String imgUrl, LocalDateTime syncedAt) {
		this.price = price;
		this.description = description;
		this.imgUrl = imgUrl;
		this.syncedAt = syncedAt;
	}

	/** 저장된 설명을 재파싱한 평문으로 교체한다(관리자 재파싱 — 다른 값은 건드리지 않는다). */
	public void reparseDescription(String cleanedDescription) {
		this.description = cleanedDescription;
	}

	/**
	 * 관리자 수정 — price/description 중 <b>실제로 바뀐 필드</b>만 갱신하고 변경 목록을 돌려준다(감사 이력용, 멱등).
	 * null 인자는 "건드리지 않음"이다(값을 비우는 수정은 빈 문자열로 온다).
	 */
	public List<FieldChange> applyAdminEdit(String price, String description) {
		List<FieldChange> changes = new ArrayList<>();
		if (price != null && !Objects.equals(this.price, price)) {
			changes.add(new FieldChange("price", this.price, price));
			this.price = price;
		}
		if (description != null && !Objects.equals(this.description, description)) {
			changes.add(new FieldChange("description", this.description, description));
			this.description = description;
		}
		return changes;
	}

	public boolean isFree() {
		return Exhibition.isFree(this.price);
	}
	/** 빈 상세는 조회용이라 저장하면 유령 행이 된다 — 영속화 시점에 막는다. */
	@PrePersist
	@PreUpdate
	private void guardSentinel() {
		if (sentinel) {
			throw new IllegalStateException("빈 상세는 저장할 수 없습니다(조회 전용).");
		}
	}

}
