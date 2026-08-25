package com.campusguard.appeal;

import com.campusguard.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/appeals")
public class AppealController {
    private final AppealService service; public AppealController(AppealService service){this.service=service;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public AppealView create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateAppealRequest request){return service.create(AuthenticatedUser.idOf(jwt),request);}
    @GetMapping public List<AppealView> mine(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="50") int size){return service.mine(AuthenticatedUser.idOf(jwt),Math.min(Math.max(size,1),100));}
}
