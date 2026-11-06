package com.ledgerly;

import com.ledgerly.shared.config.LedgerlyLimits;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LedgerlyLimits.class)
public class LedgerlyApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerlyApplication.class, args);
    }
}
