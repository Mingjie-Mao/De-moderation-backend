package com.campusguard.common;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves this service as an RFC 7807 {@code application/problem+json}
 * document.
 *
 * <p>The alternative — letting each controller shape its own error body — is why
 * clients end up parsing three different error formats from one API. Centralising
 * it also means an unexpected exception cannot leak a stack trace to a caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource not found");
        return problem;
    }

    /**
     * 429 rather than 403: the caller is permitted to do this, only not this
     * often, and the correct client behaviour is to wait rather than to change
     * the request.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Rate limit exceeded");
        return problem;
    }

    /**
     * Constraints on request parameters rather than on a request body.
     *
     * <p>Bean validation reports these through a different exception than the one
     * {@link #handleMethodArgumentNotValid} covers, so without this a query
     * parameter outside its allowed range escaped as a 500 — the caller told
     * nothing except that the server broke, for a request that was simply wrong.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> violations = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            // Drop the method name that bean validation prefixes onto the path,
            // so the client sees "size" rather than "feed.size".
            String parameter = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            violations.putIfAbsent(parameter, violation.getMessage());
        });

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more parameters are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("fieldErrors", violations);
        return problem;
    }

    /**
     * A switched-off feature is not a fault.
     *
     * <p>503 with a plain sentence, so the console can grey out the button and
     * say why. Any 4xx would read as the reviewer having done something wrong,
     * and a 500 would send an operator hunting for a break that is not there.
     */
    @ExceptionHandler(com.campusguard.moderation.investigation.InvestigationNotEnabledException.class)
    public ProblemDetail handleFeatureDisabled(
            com.campusguard.moderation.investigation.InvestigationNotEnabledException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Not enabled");
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflicting request");
        return problem;
    }

    /**
     * Raised by a service that has loaded the target and found the caller has no
     * claim to it. Authentication already succeeded at this point, so the answer
     * is 403 rather than 401: retrying with the same token will not help.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Access denied");
        return problem;
    }

    /**
     * Deliberately does not echo the exception message, which distinguishes an
     * unknown username from a wrong password and would turn login into an account
     * enumeration oracle.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        problem.setTitle("Authentication failed");
        return problem;
    }

    /**
     * Suspended and banned accounts, in contrast, are told exactly what happened:
     * the caller has already proven they own the account, and a moderation action
     * they cannot see is one they cannot appeal.
     */
    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ProblemDetail handleAccountUnavailable(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Account unavailable");
        return problem;
    }

    /**
     * The service checks for a duplicate report before inserting, but two
     * concurrent requests can both pass that check and race to the insert. The
     * unique index is what actually holds the line, so its violation has to
     * surface as the same 409 the pre-check would have produced.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with existing data.");
        problem.setTitle("Conflicting request");
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("fieldErrors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }
}
