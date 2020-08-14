package com.sktelecom.ston.controller.alice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Autowired
    GlobalService globalService;

    @PostMapping("/webhooks/topic/{topic}/")
    public ResponseEntity webhooksTopicHandler(
            @RequestHeader Map<String, String> headers,
            @PathVariable String topic,
            @RequestBody String body) {
        globalService.handleMessage(headers, topic, body);
        return ResponseEntity.ok().build();
    }

}
