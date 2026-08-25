package com.campusguard.auth;

interface PasswordResetDelivery {
    void send(String email, String username, String rawToken);
}
