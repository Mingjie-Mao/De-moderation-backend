package com.campusguard.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private static final String[] API_DOCS = {
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /**
     * Whether the OpenAPI document and its viewer are readable without a
     * credential.
     *
     * <p>True is right for development and for this project's demo, where Swagger
     * UI is the console and having to authenticate before the page can even load
     * its own spec would make it useless: the browser fetches /v3/api-docs with no
     * Authorization header, so requiring one leaves a blank page nobody can sign
     * in from.
     *
     * <p>False is right for a deployment that does not want its entire API surface
     * enumerable by anyone who asks. It is a property rather than a fixed choice
     * because the answer genuinely differs between the two, and hard-coding either
     * one makes the other a patch.
     */
    @Value("${campusguard.security.expose-api-docs:true}")
    private boolean exposeApiDocs;

    @Bean
    public SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ProblemDetailAuthErrorHandler errorHandler,
            com.campusguard.user.UserRepository users)
            throws Exception {

        http
                // Safe to disable only because this API is stateless and carries
                // its credential in an Authorization header. CSRF exists to stop a
                // browser from attaching an ambient cookie to a cross-site
                // request; nothing here is ambient. Reintroducing cookie sessions
                // would mean reinstating this.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/auth/**").permitAll()
                        // Spring forwards an unhandled exception here, and the
                        // forward is a fresh request carrying no credential. With
                        // this closed, every error on a public endpoint came back
                        // as 401 "authentication required" — which sent anyone
                        // debugging it after the wrong problem entirely, and hid
                        // the real one in the log. The body is still built by the
                        // problem-detail handler, so nothing extra is disclosed.
                        .requestMatchers("/error").permitAll()
                        // Reading the forum is open; writing to it is not. Listing
                        // each readable route rather than opening the whole prefix
                        // keeps a future write endpoint from inheriting public
                        // access by accident.
                        .requestMatchers(HttpMethod.GET, "/api/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/*/comments").permitAll()
                        // Liveness and readiness have to answer before anything
                        // is authenticated, or an orchestrator can never decide
                        // the instance is up. Detail is withheld separately, by
                        // show-details, so what an anonymous caller gets here is
                        // a single word.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Everything else under actuator is operational data —
                        // JVM internals, HTTP timings, pool sizes. An ordinary
                        // forum member has no business reading it, and the
                        // default of "any authenticated user" gave it to them.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Guarded by prefix rather than per endpoint, so a new
                        // administrative route is restricted by default instead
                        // of restricted only if somebody remembers to annotate it.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(API_DOCS).access((authentication, context) ->
                                new AuthorizationDecision(
                                        exposeApiDocs || isAdministrator(authentication.get())))
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // After the token is verified and before any authorization rule
                // reads an authority, so the rules see the account as it is now
                // rather than as it was when the token was signed.
                .addFilterAfter(new AccountStateFilter(users), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    private boolean isAdministrator(org.springframework.security.core.Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
    }

    /**
     * Spring Security's role checks look for authorities prefixed with
     * {@code ROLE_}, while the token carries the bare enum name. This converter is
     * the seam between the two vocabularies.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Deliberately slow, and salted per password by construction, so a leaked
        // table cannot be attacked with precomputed hashes.
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }

    /**
     * Pinned to HS256. Left open, a decoder will honour whatever algorithm the
     * token's own header asks for, which is the classic algorithm-confusion
     * attack: the caller picks the verification rules.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey signingKey) {
        return NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
