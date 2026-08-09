package org.granitesecurity.accounting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// The unposted-fact sweep is a @Scheduled poll. Without this it silently never runs and
// any fact that arrived before its prerequisite stays unbooked forever.
@EnableScheduling
@SpringBootApplication
public class AccountingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountingApplication.class, args);
    }

}
