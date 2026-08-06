package com.campusguard.security;

import com.campusguard.user.User;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * The subject is the user id rather than the username, so that a later rename
     * cannot silently repoint every token at a different account.
     *
     * <p>The role travels inside the token, which is what makes authorisation
     * checks free of a database read. The cost is staleness: a role changed after
     * a token was issued is not reflected until that token expires. Anything
     * destructive therefore re-reads the user rather than trusting this claim.
     */
    public String issue(User user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campusguard")
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("roles", List.of(user.getRole().name()))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }
}
