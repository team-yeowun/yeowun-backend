package modi.backend.domain.remind;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.support.entity.BaseEntity;
import modi.backend.support.error.CoreException;

/**
 * 리마인드(회고) — 과거 기록을 시간이 지난 뒤 다시 꺼내어 "지금 다시 보니 어떤가"를 남긴 것.
 * 다른 애그리거트인 {@code Record}는 {@code recordId}로만 참조하고(경계 넘는 @ManyToOne 금지),
 * 목록/카드 렌더용 전시 정보는 저장 시점 스냅샷으로 복사해 둔다.
 * 한 기록에 대해 시간차로 여러 번 만들 수 있다(Record 1 : N Remind).
 */
@Entity
@Table(name = "reminds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Remind extends BaseEntity {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "record_id", nullable = false)
	private Long recordId;

	@Column(name = "exhibition_id", nullable = false)
	private Long exhibitionId;

	@Column(name = "exhibition_title", nullable = false, length = 100)
	private String exhibitionTitle;

	@Column(name = "exhibition_poster_url", length = 2048)
	private String exhibitionPosterUrl;

	@Column(name = "exhibition_place", length = 200)
	private String exhibitionPlace;

	@Column(name = "record_viewed_at")
	private LocalDate recordViewedAt;

	/** "한 줄로 남기고 싶은 문장" — 오늘의 여운. 필수. */
	@Column(nullable = false, columnDefinition = "text")
	private String reflection;

	/** 감정 변화 AI 서술 요약(best-effort). SKIPPED/FAILED면 null. */
	@Column(name = "ai_summary", columnDefinition = "text")
	private String aiSummary;

	@Enumerated(EnumType.STRING)
	@Column(name = "ai_status", nullable = false, length = 20)
	private RemindAiStatus aiStatus;

	@OneToMany(mappedBy = "remind", cascade = CascadeType.ALL, orphanRemoval = true)
	private final List<RemindEmotion> emotions = new ArrayList<>();

	private Remind(Long userId, Long recordId, RemindExhibitionSnapshot snapshot, String reflection,
			String aiSummary, RemindAiStatus aiStatus) {
		this.userId = userId;
		this.recordId = recordId;
		this.exhibitionId = snapshot.exhibitionId();
		this.exhibitionTitle = snapshot.title();
		this.exhibitionPosterUrl = snapshot.posterUrl();
		this.exhibitionPlace = snapshot.place();
		this.recordViewedAt = snapshot.viewedAt();
		this.reflection = reflection;
		this.aiSummary = aiSummary;
		this.aiStatus = aiStatus;
	}

	public static Remind create(Long userId, Long recordId, RemindExhibitionSnapshot snapshot, String reflection,
			List<String> emotionCodes, String aiSummary, RemindAiStatus aiStatus) {
		Remind remind = new Remind(userId, recordId, snapshot, reflection, aiSummary, aiStatus);
		if (emotionCodes != null) {
			emotionCodes.stream()
					.filter(code -> code != null && !code.isBlank())
					.distinct()
					.map(String::trim)
					.map(RemindEmotion::create)
					.forEach(remind::addEmotion);
		}
		return remind;
	}

	/** 백그라운드에서 생성된 감정 변화 AI 요약을 반영한다(PENDING → READY/SKIPPED/FAILED). SKIPPED/FAILED면 요약 null. */
	public void applyAiSummary(RemindAiStatus aiStatus, String aiSummary) {
		this.aiStatus = aiStatus;
		this.aiSummary = aiSummary;
	}

	/** {@code RULE: 소감 필수} — reflection은 공백일 수 없다. */
	@Override
	protected void guard() {
		if (reflection == null || reflection.isBlank()) {
			throw new CoreException(RemindErrorCode.INVALID_REMIND_INPUT, "소감을 입력해 주세요.");
		}
	}

	private void addEmotion(RemindEmotion emotion) {
		emotion.attach(this);
		emotions.add(emotion);
	}
}
