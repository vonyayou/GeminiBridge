package me.vonyayou.geminibridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GeminiBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeminiBridgeApplication.class, args);
    }
}
