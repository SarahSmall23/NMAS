package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Notification;
import za.co.nmas.nmas.repository.NotificationRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Guards against the exact same notification firing twice for the same
     * person within a short window. This matters because several call sites
     * loop over multiple recipients or can be triggered twice in quick
     * succession (a retry, a double form submit, two near-simultaneous
     * requests) — without this, the same message could land in someone's
     * notification list more than once for the same real-world event.
     *
     * Keyed on recipient + the exact message text, so it only blocks a
     * truly identical notification, not two different real events that
     * happen to share a type/link (e.g. two different patients booking the
     * same doctor within a few seconds of each other must both notify him).
     *
     * The check-and-record step uses ConcurrentHashMap.compute(), which is
     * atomic for a single key — a plain get() followed by a put() has a
     * race: two near-simultaneous calls could both read "nothing sent yet"
     * before either writes, letting both duplicates through despite the
     * guard being there.
     */
    private static final Map<String, Long> recentSends = new ConcurrentHashMap<>();
    private static final long DUPLICATE_WINDOW_MS = 10_000; // 10 seconds

    public void notify(String recipientEmail, String type, String message, String link) {
        if (recipientEmail == null || recipientEmail.isBlank()) return;
        if (message == null || message.isBlank()) return;

        String dedupeKey = recipientEmail.trim().toLowerCase() + "|" + message;
        long now = System.currentTimeMillis();

        boolean[] isDuplicate = { false };
        recentSends.compute(dedupeKey, (key, lastSent) -> {
            if (lastSent != null && (now - lastSent) < DUPLICATE_WINDOW_MS) {
                isDuplicate[0] = true;
                return lastSent; // leave the original timestamp — don't extend the window
            }
            return now;
        });
        if (isDuplicate[0]) return; // identical notification for this person was just sent — skip the duplicate

        // Keep this map from growing forever on a long-running server —
        // occasionally sweep out anything older than the window.
        if (recentSends.size() > 500) {
            recentSends.entrySet().removeIf(e -> (now - e.getValue()) > DUPLICATE_WINDOW_MS);
        }

        Notification n = new Notification();
        n.setRecipientEmail(recipientEmail);
        n.setType(type);
        n.setMessage(message);
        n.setLink(link);
        notificationRepository.save(n);
    }

    public List<Notification> findRecent(String email) {
        return notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(email)
                .stream().limit(20).toList();
    }

    public long countUnread(String email) {
        return notificationRepository.countByRecipientEmailAndIsReadFalse(email);
    }

    public void markRead(Long id) {
        notificationRepository.findById(id).ifPresent(n -> {
            n.setRead(true);
            notificationRepository.save(n);
        });
    }

    public void markAllRead(String email) {
        List<Notification> all = notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(email);
        all.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(all);
    }
}
