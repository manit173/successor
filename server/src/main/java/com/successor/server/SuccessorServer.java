package com.successor.server;

import com.successor.engine.ScoutingEngine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * The Java backend. Runs standalone ({@code mvn -pl server spring-boot:run}, for curl and API examples) or embedded
 * in the macOS app, which starts it on a free loopback port and talks to it over HTTP like any other client.
 */
@SpringBootApplication
public class SuccessorServer {

    public static void main(String[] args) {
        SpringApplication.run(SuccessorServer.class, args);
    }

    /**
     * {@code --successor.data=<dir>} picks a dataset folder; otherwise {@code data/processed} (real, built by
     * scripts/build_data.sh), then the committed synthetic {@code data/sample}.
     */
    @Bean
    ScoutingEngine scoutingEngine(@Value("${successor.data:}") String dataDir) {
        return ScoutingEngine.load(dataDir.isBlank() ? null : Path.of(dataDir));
    }
}
