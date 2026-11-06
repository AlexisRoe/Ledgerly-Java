package com.ledgerly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ledgerly: expense reporting for teams, as a modular monolith (modules: identity, teams, expenses, receipts, imports, audit).
 */
@SpringBootApplication
public class LedgerlyApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerlyApplication.class, args);
    }
}
