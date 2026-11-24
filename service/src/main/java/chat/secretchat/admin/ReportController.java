package chat.secretchat.admin;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
class ReportController {
    private final DirectoryService directory;
    private final ReportStore reports;
    private final ReportKey key;
    private final ObjectMapper mapper;

    ReportController(DirectoryService directory, ReportStore reports, ReportKey key, ObjectMapper mapper) {
        this.directory = directory;
        this.reports = reports;
        this.key = key;
        this.mapper = mapper;
    }

    @GetMapping("/api/reports/key")
    ReportKey.Recipient recipient(@RequestHeader(value="Authorization",required=false) String authorization) {
        directory.requireDevice(authorization);
        return key.recipient();
    }

    @PostMapping(value="/api/reports", consumes="application/json")
    public Map<String,String> submit(@RequestHeader(value="Authorization",required=false) String authorization,
                                     HttpServletRequest request) throws IOException {
        var device = directory.requireDevice(authorization);
        var submission = ReportSubmission.read(request.getInputStream(), mapper, key);
        reports.submit(device, submission);
        return Map.of("id",submission.id());
    }

    @GetMapping("/admin-api/reports")
    List<ReportStore.Summary> list(Principal user, @RequestParam(defaultValue="9223372036854775807") long before) {
        return reports.list(user.getName(), before);
    }

    @GetMapping("/admin-api/reports/{id}")
    ReportStore.Detail read(Principal user, @PathVariable String id) {
        return reports.read(user.getName(), ReportSubmission.uuid(id));
    }

    @PostMapping("/admin-api/reports/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(Principal user, @PathVariable String id) {
        reports.close(user.getName(), ReportSubmission.uuid(id));
    }
}
