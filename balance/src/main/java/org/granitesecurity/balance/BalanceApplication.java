package org.granitesecurity.balance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// The outbox relay is a @Scheduled poll; without this it silently never runs and
// balance.events stays empty while the outbox fills up.
@EnableScheduling
@SpringBootApplication
public class BalanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BalanceApplication.class, args);
    }

}
