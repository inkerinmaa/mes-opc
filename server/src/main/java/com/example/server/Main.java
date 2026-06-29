package com.example.server;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Entry point for the OPC UA Server Quarkus application.
 *
 * Using @QuarkusMain + QuarkusApplication keeps the process alive until
 * SIGTERM/SIGINT, giving CDI beans a chance to run their @Observes
 * StartupEvent / ShutdownEvent lifecycle callbacks.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        System.out.println("[SERVER] OPC UA Server application starting…");
        Quarkus.waitForExit();   // blocks until Quarkus shuts down
        return 0;
    }
}
