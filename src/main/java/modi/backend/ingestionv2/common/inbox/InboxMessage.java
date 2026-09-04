package modi.backend.ingestionv2.common.inbox;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** subscriber별 이벤트 처리권과 종결 상태. 상태 전이는 조건부 SQL이 담당한다. */
@Entity(name = "IngestionV2Inbox")
@Table(name = "ingestion_inbox", uniqueConstraints = @UniqueConstraint(
		name = "uk_ingestion_inbox_subscriber_event", columnNames = {"subscriber_key", "event_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "subscriber_key", nullable = false, length = 100)
	private String subscriberKey;

	@Column(name = "event_id", nullable = false, length = 36)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InboxStatus status;

	@Column(name = "claim_token", length = 36)
	private String claimToken;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "lease_until")
	private LocalDateTime leaseUntil;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "last_error", length = 1000)
	private String lastError;
}
