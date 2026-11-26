package chat.secretchat.admin;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OpsController {
    private final DeliveryProviders providers;
    private final OpsAudit audit;
    OpsController(DeliveryProviders providers, OpsAudit audit) {
        this.providers = providers;
        this.audit = audit;
    }

    record Session(String username, List<String> roles) {}

    @GetMapping("/admin-api/me")
    Session session(Authentication auth) {
        return new Session(auth.getName(), auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .filter(role -> role.startsWith("ROLE_")).map(role -> role.substring(5)).toList());
    }

    @GetMapping("/admin-api/status")
    Map<String,Object> status() {
        return providers.status();
    }

    @GetMapping("/admin-api/audit")
    List<OpsAudit.Event> history(Principal user, @RequestParam(defaultValue="9223372036854775807") long before) {
        return audit.history(user.getName(), before);
    }
}
