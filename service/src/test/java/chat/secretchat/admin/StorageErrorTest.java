package chat.secretchat.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class StorageErrorTest {
    @RestController
    static class BrokenStorage {
        @GetMapping("/test")
        String read() {
            throw new DataAccessResourceFailureException("fixture-sensitive-value");
        }
    }

    @Test void failedStorageDoesNotDiscloseVendorExceptionDetails() throws Exception {
        MockMvcBuilders.standaloneSetup(new BrokenStorage()).setControllerAdvice(new StorageErrorHandler())
            .build().perform(get("/test")).andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    }
}
