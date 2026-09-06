package com.campusguard.moderation.investigation;

/**
 * The assistant is switched off.
 *
 * <p>A configuration state rather than a failure, which is why it is its own type
 * and not a conflict or a not-found. The console shows it as a disabled button
 * with a reason; anything that read as an error would send an operator looking
 * for a fault that is not there.
 */
public class InvestigationNotEnabledException extends RuntimeException {

    public InvestigationNotEnabledException() {
        super("Case investigation is not enabled on this deployment.");
    }
}
