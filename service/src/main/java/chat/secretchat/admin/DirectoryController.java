package chat.secretchat.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
class DirectoryController {
    private static final java.util.Set<String> LOCAL_PROXY = java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    record ChallengeRequest(String email, String purpose) {}
    record Limit(Integer limit) {}
    record Redeem(String code) {}

    private final DirectoryService directory;
    private final DirectoryInvites invites;
    private final DirectoryPrekeys prekeys;
    private final ChallengeService challenges;
    DirectoryController(DirectoryService directory, DirectoryInvites invites, DirectoryPrekeys prekeys, ChallengeService challenges) {
        this.directory = directory;
        this.invites = invites;
        this.prekeys = prekeys;
        this.challenges = challenges;
    }

    @GetMapping("/admin-api/invites/settings")
    DirectoryInvites.Settings settings(Principal operator) { return invites.adminSettings(operator.getName()); }

    @PutMapping("/admin-api/invites/settings")
    DirectoryInvites.Settings limit(Principal operator, @RequestBody Limit request) {
        return invites.changeLimit(operator.getName(), request.limit());
    }

    @PostMapping("/admin-api/invites")
    DirectoryInvites.Issued bootstrap(Principal operator) { return invites.bootstrap(operator.getName()); }

    @PostMapping("/api/challenges")
    ChallengeService.Ticket challenge(HttpServletRequest request, @RequestBody ChallengeRequest body) {
        String ip = request.getRemoteAddr();
        if (LOCAL_PROXY.contains(ip)) {
            String proxyAddress = request.getHeader("X-Real-IP");
            if (proxyAddress != null && proxyAddress.length() <= 45 && proxyAddress.matches("[a-fA-F0-9.:]+")) ip = proxyAddress;
        }
        return challenges.issue(body.email(), body.purpose(), ip);
    }

    @PostMapping("/api/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    DirectoryService.Access register(@RequestBody DirectoryService.AccountRequest body) { return directory.register(body); }

    @PostMapping("/api/accounts/recover")
    DirectoryService.Access recover(@RequestBody DirectoryService.AccountRequest body) { return directory.recover(body); }

    @DeleteMapping("/api/accounts/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(HttpServletRequest request) { directory.close(device(request)); }

    @GetMapping("/api/contacts")
    List<DirectoryService.Contact> contacts(HttpServletRequest request) { return directory.contacts(device(request)); }

    @PutMapping("/api/contacts/{peer}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(HttpServletRequest request, @PathVariable UUID peer) { directory.block(device(request), peer.toString(), true); }

    @DeleteMapping("/api/contacts/{peer}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(HttpServletRequest request, @PathVariable UUID peer) { directory.block(device(request), peer.toString(), false); }

    @GetMapping("/api/invites")
    List<DirectoryInvites.Entry> invitations(HttpServletRequest request) { return directory.invitations(device(request)); }

    @PostMapping("/api/invites")
    DirectoryInvites.Issued invite(HttpServletRequest request) { return directory.invite(device(request)); }

    @DeleteMapping("/api/invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(HttpServletRequest request, @PathVariable UUID id) { directory.revoke(device(request), id.toString()); }

    @PostMapping("/api/invites/redeem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void redeem(HttpServletRequest request, @RequestBody Redeem redeem) { directory.redeem(device(request), redeem.code()); }

    @PostMapping("/api/contacts/{peer}/prekeys")
    DirectoryPrekeys.Bundle claim(HttpServletRequest request, @PathVariable UUID peer) {
        return prekeys.claim(device(request), peer.toString());
    }

    @PostMapping("/api/keys/one-time")
    DirectoryPrekeys.Count upload(HttpServletRequest request, @RequestBody DirectoryPrekeys.Batch batch) {
        return prekeys.upload(device(request), batch);
    }

    @GetMapping("/api/keys/one-time")
    DirectoryPrekeys.Count remaining(HttpServletRequest request) { return prekeys.count(device(request)); }

    @PutMapping("/api/keys/signed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateSigned(HttpServletRequest request, @RequestBody DirectoryPrekeys.Signed signed) {
        prekeys.updateSigned(device(request), signed);
    }

    private DeviceIdentity device(HttpServletRequest request) { return directory.requireDevice(request.getHeader("Authorization")); }
}
