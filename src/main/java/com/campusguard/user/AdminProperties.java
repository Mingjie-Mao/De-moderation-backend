package com.campusguard.user;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The administrator account created at startup, if it does not already exist.
 *
 * <p>Both values default to empty rather than to something usable. An
 * administrator that appears because nobody set a property is an administrator
 * whose password is in this repository.
 *
 * @param username the account to create; blank disables the whole mechanism
 * @param password its initial password, only ever read when the account is
 *     being created
 */
@ConfigurationProperties(prefix = "campusguard.admin")
public record AdminProperties(@DefaultValue("") String username, @DefaultValue("") String password) {

    boolean configured() {
        return !username.isBlank() && !password.isBlank();
    }
}
