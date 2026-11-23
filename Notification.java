package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login email of whoever should see this notification (doctor or patient). */
    @Column(nullable = false)
    private String recipientEmail;

    @Column(nullable = false)
    private String type; // VIDEO_REQUESTED, VIDEO_ACCEPTED, PRESCRIPTION_RECEIVED

    @Column(nullable = false, length = 500)
    private String message;

    /** Where clicking the notification should take the user, e.g. /video/incoming */
    private String link;

    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public Notification() {}

    public Long getId() { return id; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
