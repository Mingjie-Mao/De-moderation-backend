package com.campusguard.security;

import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-reads the acting account on every authenticated request, so a ban takes
 * effect at once rather than whenever the token happens to expire.
 *
 * <p>A signed token is a statement about the past: it says who this was and what
 * they were allowed to do at the moment it was issued. For most of an API that
 * is close enough. Here it is not, because banning is the strongest thing the
 * moderation console can do and it is aimed at somebody actively causing harm.
 * With a one-hour token and nothing but claims to go on, that person keeps
 * posting for up to another hour after a moderator has decided they should stop
 * — measured, not assumed: a suspended account's pre-ban token created a post
 * with a 201.
 *
 * <p>The same staleness runs the other way. A demoted administrator carried
 * {@code ROLE_ADMIN} in their token until it expired, which meant an hour of
 * resolving cases and banning other people after losing the role. So authorities
 * are rebuilt from the stored role rather than from the claim, and the claim is
 * left to identify the subject and nothing else.
 *
 * <p>Deliberately not a {@code @Component}. Spring Boot auto-registers any
 * {@link jakarta.servlet.Filter} bean straight into the servlet container's
 * chain, so annotating it would install it twice — once where this class asks to
 * be, and once outside the security chain entirely, at an ordering nobody chose.
 * Constructing it in the security configuration keeps it to the one position
 * that was reasoned about.
 *
 * <p>The cost is one primary-key lookup per authenticated request. That is the
 * right trade for a forum: enforcement that waits an hour is not enforcement,
 * and the alternatives — a revocation list, or short tokens plus refresh — are
 * both more machinery than a table this size justifies.
 */
public class AccountStateFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public AccountStateFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken token) {
            SecurityContextHolder.getContext().setAuthentication(revalidate(token));
        }

        chain.doFilter(request, response);
    }

    /**
     * An account that has been deleted outright is treated exactly like one that
     * was suspended. Either way the token names somebody who is not entitled to
     * act, and saying which would tell an unauthenticated caller whether a given
     * id ever existed.
     */
    private Authentication revalidate(JwtAuthenticationToken token) {
        Jwt jwt = token.getToken();
        UUID id;
        try {
            id = AuthenticatedUser.idOf(jwt);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("The token's subject is not a user id.");
        }

        User user = Optional.of(id)
                .flatMap(users::findById)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidBearerTokenException(
                        "This account can no longer act. Sign in again."));

        Number claimedVersion = jwt.getClaim("ver");
        int tokenVersion = claimedVersion == null ? 0 : claimedVersion.intValue();
        if (tokenVersion != user.getTokenVersion()) {
            throw new InvalidBearerTokenException("This session has ended. Sign in again.");
        }

        List<GrantedAuthority> current =
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        AbstractAuthenticationToken refreshed = new JwtAuthenticationToken(jwt, current, user.getUsername());
        refreshed.setDetails(token.getDetails());
        return refreshed;
    }
}
