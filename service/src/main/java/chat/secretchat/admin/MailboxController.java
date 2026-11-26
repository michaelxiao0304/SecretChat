package chat.secretchat.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mailbox")
class MailboxController {
    private final DirectoryService directory;
    private final MailboxService mailbox;
    MailboxController(DirectoryService directory, MailboxService mailbox) {
        this.directory = directory;
        this.mailbox = mailbox;
    }

    @PostMapping("/envelopes") @ResponseStatus(HttpStatus.ACCEPTED)
    MailboxService.Receipt submit(@RequestHeader(value="Authorization", required=false) String authorization,
                                  @RequestBody MailboxService.Submission submission) {
        return mailbox.submit(directory.requireDevice(authorization), submission);
    }

    @GetMapping("/envelopes")
    MailboxService.Page page(@RequestHeader(value="Authorization", required=false) String authorization,
                             @RequestParam(defaultValue="0") long after, @RequestParam(required=false) Long through,
                             @RequestParam(defaultValue="50") int limit) {
        return mailbox.page(directory.requireDevice(authorization), after, through, limit);
    }

    @GetMapping("/envelopes/{ref}")
    MailboxService.Envelope envelope(@RequestHeader(value="Authorization", required=false) String authorization,
                                    @PathVariable String ref) {
        return mailbox.envelope(directory.requireDevice(authorization), ref);
    }

    @PostMapping("/envelopes/{ref}/ack") @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable String ref) {
        mailbox.acknowledge(directory.requireDevice(authorization), ref);
    }

    @GetMapping("/outcomes/{ref}")
    MailboxService.Outcome outcome(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable String ref) {
        return mailbox.outcome(directory.requireDevice(authorization), ref);
    }

    record Token(String token) {}
    @PutMapping("/push-token") @ResponseStatus(HttpStatus.NO_CONTENT)
    void bind(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Token binding) {
        mailbox.bind(directory.requireDevice(authorization), binding.token());
    }

    @DeleteMapping("/push-token") @ResponseStatus(HttpStatus.NO_CONTENT)
    void unbind(@RequestHeader(value="Authorization", required=false) String authorization) {
        mailbox.unbind(directory.requireDevice(authorization));
    }
}
