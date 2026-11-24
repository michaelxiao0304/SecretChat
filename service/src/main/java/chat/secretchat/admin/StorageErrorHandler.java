package chat.secretchat.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class StorageErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StorageErrorHandler.class);

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable(DataAccessException failure) {
        // Vendor exception messages can include a mailbox token or an account identifier.
        LOG.warn("Database request failed ({})", failure.getClass().getSimpleName());
    }
}
