package com.sktelecom.ston.controller.faber;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.sktelecom.ston.controller.utils.Common.prettyJson;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    private final GlobalService globalService;

    @PostMapping("/webhooks/topic/{topic}/")
    public ResponseEntity webhooksTopicHandler(
            @PathVariable String topic,
            @RequestBody String body) {
        log.info("webhooksTopicHandler - topic:" + topic + ",body:" + prettyJson(body));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/invitations")
    public String invitationsHandler() {
        log.debug("invitationsHandler >>>");

        String response = "invitation";

        log.debug("invitationsHandler <<< " + response);
        return response;
    }

}
