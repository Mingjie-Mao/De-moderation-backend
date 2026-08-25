package com.campusguard.audit;

public enum AuditActorType {

    /** An ordinary member acting on their own content or filing a report. */
    USER,

    /** A moderation engine. Has no account, so the entry carries no actor id. */
    ENGINE,

    /** An administrator exercising moderation powers. */
    ADMIN,

    /** The application itself: queue transitions and other unattended work. */
    SYSTEM
}
