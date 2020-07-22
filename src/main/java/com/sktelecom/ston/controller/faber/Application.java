package com.sktelecom.ston.controller.faber;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class Application {

    public static void main(String[] args) {
        // GlobalService.initialize() is automatically called before this application starts.
        SpringApplication.run(Application.class, args);
    }
}
