package com.campusguard.appeal;

import com.campusguard.audit.*;
import com.campusguard.common.*;
import com.campusguard.moderation.*;
import com.campusguard.moderation.admin.*;
import com.campusguard.notification.NotificationService;
import com.campusguard.user.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppealService {
    private final AppealRepository appeals; private final ModerationCaseRepository cases;
    private final ContentLocator content; private final UserRepository users;
    private final AdminModerationService moderation; private final NotificationService notifications;
    private final AuditLogger audit;
    public AppealService(AppealRepository appeals, ModerationCaseRepository cases, ContentLocator content,
            UserRepository users, AdminModerationService moderation, NotificationService notifications, AuditLogger audit) {
        this.appeals=appeals; this.cases=cases; this.content=content; this.users=users;
        this.moderation=moderation; this.notifications=notifications; this.audit=audit;
    }
    @Transactional
    public AppealView create(UUID appellantId, CreateAppealRequest request) {
        User appellant=users.findById(appellantId).orElseThrow(() -> new NotFoundException("No user with id "+appellantId));
        ModerationCase c=cases.findById(request.caseId()).orElseThrow(() -> new NotFoundException("No moderation case with id "+request.caseId()));
        if (c.getStatus()!=CaseStatus.RESOLVED || c.getFinalAction()==FinalAction.NONE)
            throw new ConflictException("Only a moderation action that affects content or an account can be appealed.");
        UUID author=content.authorOf(c.getTargetType(),c.getTargetId()).orElse(null);
        if (!appellantId.equals(author)) throw new org.springframework.security.access.AccessDeniedException("Only the affected author can appeal this case.");
        if (appeals.existsByModerationCaseIdAndAppellantIdAndStatus(c.getId(),appellantId,AppealStatus.PENDING))
            throw new ConflictException("A pending appeal already exists for this case.");
        Appeal saved=appeals.saveAndFlush(new Appeal(c,appellant,request.reason()));
        audit.record(AuditActorType.USER,appellantId,AuditLogger.APPEAL_FILED,c.getTargetType(),c.getTargetId(),Map.of("caseId",c.getId().toString(),"appealId",saved.getId().toString()));
        users.findAllByRole(UserRole.ADMIN).forEach(admin -> notifications.create(admin,"APPEAL_FILED","A new appeal needs review","An author appealed moderation case "+c.getId()+".","APPEAL",saved.getId()));
        return AppealView.of(saved);
    }
    @Transactional(readOnly=true)
    public List<AppealView> mine(UUID userId,int size){return appeals.findByAppellantIdOrderByCreatedAtDesc(userId,PageRequest.of(0,size)).stream().map(AppealView::of).toList();}
    @Transactional(readOnly=true)
    public List<AppealView> list(AppealStatus status,int size){return appeals.findByStatusOrderByCreatedAtAsc(status,PageRequest.of(0,size)).stream().map(AppealView::of).toList();}
    @Transactional
    public AppealView decide(UUID adminId,UUID appealId,AppealDecisionRequest request){
        User admin=users.findById(adminId).orElseThrow(() -> new NotFoundException("No user with id "+adminId));
        Appeal appeal=appeals.findByIdForUpdate(appealId).orElseThrow(() -> new NotFoundException("No appeal with id "+appealId));
        if(appeal.getStatus()!=AppealStatus.PENDING) throw new ConflictException("This appeal has already been decided.");
        ModerationCase c=appeal.getModerationCase();
        if(request.decision()==AppealDecision.OVERTURN)
            moderation.decide(adminId,c.getId(),new CaseDecisionRequest(FinalAction.NONE,"Appeal overturned: "+String.valueOf(request.response())));
        appeal.decide(admin,request.decision(),request.response());
        notifications.create(appeal.getAppellant(),"APPEAL_DECISION","Your appeal was decided","The appeal was "+appeal.getStatus()+".","APPEAL",appeal.getId());
        audit.record(AuditActorType.ADMIN,adminId,AuditLogger.APPEAL_DECIDED,c.getTargetType(),c.getTargetId(),Map.of("caseId",c.getId().toString(),"appealId",appeal.getId().toString(),"decision",request.decision().name()));
        return AppealView.of(appeal);
    }
}
