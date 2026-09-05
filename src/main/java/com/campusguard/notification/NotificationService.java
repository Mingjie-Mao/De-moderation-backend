package com.campusguard.notification;

import com.campusguard.common.NotFoundException;
import com.campusguard.user.User;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final NotificationRepository repository;
    public NotificationService(NotificationRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(User user, String type, String title, String body, String referenceType, UUID referenceId) {
        repository.save(new Notification(user, type, title, body, referenceType, referenceId));
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(UUID userId, int size) {
        return repository.findForUser(userId, PageRequest.of(0, size)).stream().map(NotificationView::of).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) { return repository.countByUserIdAndReadAtIsNull(userId); }

    @Transactional
    public NotificationView markRead(UUID userId, UUID id) {
        Notification notification = repository.findOwned(id, userId)
                .orElseThrow(() -> new NotFoundException("No notification with id " + id));
        notification.markRead();
        return NotificationView.of(notification);
    }
}
