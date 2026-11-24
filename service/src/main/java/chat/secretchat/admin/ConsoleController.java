package chat.secretchat.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
class ConsoleController {
    record SessionToken(String header, String token) {}

    @GetMapping("/console")
    ResponseEntity<Void> canonicalConsole() {
        return ResponseEntity.status(308).header("Location", "console/").build();
    }

    @GetMapping("/console/")
    String console() {
        return "forward:/console/index.html";
    }

    @GetMapping("/admin-api/session")
    @ResponseBody SessionToken session(CsrfToken csrf) {
        return new SessionToken(csrf.getHeaderName(), csrf.getToken());
    }
}
