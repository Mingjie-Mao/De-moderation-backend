package com.campusguard.appeal;

import com.campusguard.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/appeals")
public class AdminAppealController {
    private final AppealService service; public AdminAppealController(AppealService service){this.service=service;}
    @GetMapping public List<AppealView> list(@RequestParam(defaultValue="PENDING") AppealStatus status,@RequestParam(defaultValue="100") int size){return service.list(status,Math.min(Math.max(size,1),100));}
    @PostMapping("/{id}/decision") public AppealView decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody AppealDecisionRequest request){return service.decide(AuthenticatedUser.idOf(jwt),id,request);}
}
