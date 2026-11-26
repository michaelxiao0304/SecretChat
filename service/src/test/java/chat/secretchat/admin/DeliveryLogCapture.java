package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

final class DeliveryLogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    DeliveryLogCapture(Class<?> source) {
        logger = (Logger) LoggerFactory.getLogger(source);
        appender.start();
        logger.addAppender(appender);
    }

    List<String> messages() {
        assertThat(appender.list).allSatisfy(entry -> assertThat(entry.getThrowableProxy()).isNull());
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
