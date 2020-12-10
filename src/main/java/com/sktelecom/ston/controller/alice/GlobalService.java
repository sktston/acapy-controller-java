package com.sktelecom.ston.controller.alice;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Timer;
import java.util.TimerTask;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    final String agentApiUrl = "http://localhost:8031";
    final String faberContUrl = "http://localhost:8022";

    // check options
    static boolean enableOob = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_OOB", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("Receive invitation from faber controller");
        if (enableOob)
            receiveOobInvitation();
        else
            receiveInvitation();
    }

    public void handleMessage(String topic, String body) {
        log.info("handleMessage >>> topic:" + topic + ", body:" + body);

        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        switch(topic) {
            case "connections":
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                break;
            case "issue_credential":
                // When credential offer is received, send credential request
                if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    sendCredentialRequest(JsonPath.read(body, "$.credential_exchange_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "present_proof":
                // When proof request is received, send proof(presentation)
                if (state.equals("request_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProof");
                    sendProof(body);
                }
                else if (state.equals("presentation_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> delayedExit");
                    delayedExit();
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "basicmessages":
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print message");
                log.info("  - message:" + JsonPath.read(body, "$.content"));
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void receiveInvitation() {
        log.info("receiveInvitation >>>");
        String invitation = requestGET(faberContUrl + "/invitation");
        log.info("invitation:" + invitation);
        String response = requestPOST(agentApiUrl + "/connections/receive-invitation", invitation);
        log.info("receiveInvitation <<<");
    }

    public void receiveOobInvitation() {
        log.info("receiveOobInvitation >>>");
        String invitation = requestGET(faberContUrl + "/oob-invitation");
        log.info("oob-invitation:" + invitation);
        String response = requestPOST(agentApiUrl + "/didexchange/receive-invitation", invitation);
        log.info("receiveOobInvitation <<<");
    }

    public void sendCredentialRequest(String credExId) {
        String response = requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", "{}");
    }

    public void sendProof(String reqBody) {
        String presExId = JsonPath.read(reqBody, "$.presentation_exchange_id");
        String response = requestGET(agentApiUrl + "/present-proof/records/" + presExId + "/credentials");

        ArrayList<LinkedHashMap<String, Object>> credentials = JsonPath.read(response, "$");
        int credRevId = 0;
        String credId = null;
        for (LinkedHashMap<String, Object> element : credentials) {
            if (JsonPath.read(element, "$.cred_info.cred_rev_id") != null){ // case of support revocation
                int curCredRevId = Integer.parseInt(JsonPath.read(element, "$.cred_info.cred_rev_id"));
                if (curCredRevId > credRevId) {
                    credRevId = curCredRevId;
                    credId = JsonPath.read(element, "$.cred_info.referent");
                }
            }
            else { // case of not support revocation
                credId = JsonPath.read(element, "$.cred_info.referent");
            }
        }
        log.info("Use latest credential in demo - credRevId: " + credRevId + ", credId: "+ credId);

        // Make body using presentation_request
        LinkedHashMap<String, Object> reqAttrs = JsonPath.read(reqBody, "$.presentation_request.requested_attributes");
        for(String key : reqAttrs.keySet())
            reqAttrs.replace(key, JsonPath.parse("{ cred_id: '" + credId + "', revealed: true }").json());

        LinkedHashMap<String, Object> reqPreds = JsonPath.read(reqBody, "$.presentation_request.requested_predicates");
        for(String key : reqPreds.keySet())
            reqPreds.replace(key, JsonPath.parse("{ cred_id: '" + credId + "' }").json());

        LinkedHashMap<String, Object> selfAttrs = new LinkedHashMap<>();

        String body = JsonPath.parse("{}").put("$", "requested_attributes", reqAttrs)
                .put("$", "requested_predicates", reqPreds)
                .put("$", "self_attested_attributes", selfAttrs).jsonString();

        response = requestPOST(agentApiUrl + "/present-proof/records/" + presExId + "/send-presentation", body);
    }

    public void delayedExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                log.info("Alice demo completes - Exit");
                System.exit(0);
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 100L);
    }
}