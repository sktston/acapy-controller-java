package com.sktelecom.ston.controller.faber;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Autowired
    GlobalService globalService;

    @GetMapping("/invitation")
    public String invitationHandler() {
        return globalService.createInvitation();
    }

    @GetMapping("/invitation-url")
    public String invitationUrlHandler() {
        return globalService.createInvitationUrl();
    }

    @PostMapping("/webhooks/topic/{topic}")
    public ResponseEntity webhooksTopicHandler(
            @PathVariable String topic,
            @RequestBody String body) {
        globalService.handleEvent(topic, body);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/invitation-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getInvitationUrlQRCode() {
        String invitationUrl = globalService.createInvitationUrl();
        return generateQRCode(invitationUrl, 300, 300);
    }

}
