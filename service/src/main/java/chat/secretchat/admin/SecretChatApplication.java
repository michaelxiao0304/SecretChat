package chat.secretchat.admin;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SecretChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecretChatApplication.class, args);
    }

    @Bean Clock clock() {
        return Clock.systemUTC();
    }
}
