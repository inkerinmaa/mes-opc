package com.example.client;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Entry point for the OPC UA Client Quarkus application.
 * Keeps the process alive so the scheduler and subscription callbacks
 * continue running until SIGTERM/SIGINT.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        System.out.println("[CLIENT] OPC UA Client application starting…");
        Quarkus.waitForExit();
        return 0;
    }
}
