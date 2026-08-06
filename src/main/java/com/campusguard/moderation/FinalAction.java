package com.campusguard.moderation;

public enum FinalAction {

    /** Reviewed and left alone. Recorded rather than silent, so a dismissed report is still evidence. */
    NONE,

    /** The content is soft-deleted. */
    HIDE,

    /** Same effect as HIDE today, kept distinct because the two carry different intent in an audit trail. */
    DELETE,

    /** The content is removed and its author is banned. */
    BAN
}
