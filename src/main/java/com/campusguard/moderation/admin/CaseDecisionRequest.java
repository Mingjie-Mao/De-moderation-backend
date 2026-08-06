package com.campusguard.moderation.admin;

import com.campusguard.moderation.FinalAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param note why the administrator decided this way. Optional, but it is the
 *     only part of the record that captures reasoning a rule code cannot, and
 *     the part that matters most when a decision is questioned later.
 */
public record CaseDecisionRequest(@NotNull FinalAction action, @Size(max = 1000) String note) {
}
