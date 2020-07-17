package com.sktelecom.ston.controller.faber;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Service
@Slf4j
public class GlobalService {

    @PostConstruct
    public void initialize(){
        log.info("initialize >>> start");

        log.info("initialize <<< done");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("initializeAfterStartup >>> start");

        log.info("initializeAfterStartup <<< done");
    }

    @PreDestroy
    public void destroy(){
        log.info("destroy >>> start");

        log.info("destroy <<< done");
    }

}