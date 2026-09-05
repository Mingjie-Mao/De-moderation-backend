package com.campusguard.user;

import com.campusguard.common.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public MyProfileView me(UUID userId) {
        return MyProfileView.of(requireUser(userId));
    }

    @Transactional(readOnly = true)
    public UserView get(UUID userId) {
        return UserView.of(requireUser(userId));
    }

    @Transactional
    public MyProfileView update(UUID userId, UpdateProfileRequest request) {
        User user = requireUser(userId);
        // PATCH semantics: a missing field is retained; an explicit blank bio
        // clears it. Without this, changing only a display name erased the bio.
        user.updateProfile(
                request.displayName() == null ? user.getDisplayName() : request.displayName(),
                request.bio() == null ? user.getBio() : request.bio());
        return MyProfileView.of(user);
    }

    private User requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("No user with id " + userId));
    }
}
