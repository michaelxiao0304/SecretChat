package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class DirectoryProofTest {
    @Test void proofMustMatchPurposeLiveAccountExpiryAndAttemptBudget() {
        var verify = new ChallengeService.Verification("name@example.test","recover",new byte[32],100,1,"account");
        assertThat(verify.validFor("recover",99)).isTrue();
        assertThat(verify.validFor("recover",100)).isFalse();
        assertThat(verify.validFor("register",99)).isFalse();
        var missing = new ChallengeService.Verification("name@example.test","recover",new byte[32],100,1,null);
        assertThat(missing.validFor("recover",99)).isFalse();
        var locked = new ChallengeService.Verification("name@example.test","recover",new byte[32],100,0,"account");
        assertThat(locked.validFor("recover",99)).isFalse();
        var signup = new ChallengeService.Verification("name@example.test","register",new byte[32],100,1,null);
        assertThat(signup.validFor("register",99)).isTrue();
    }
}
