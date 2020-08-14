package com.sktelecom.ston.controller.alice;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    final String adminUrl = "http://localhost:8031";
    final String faberContUrl = "http://localhost:8022";

    String walletName = "alice.agent";

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("initializeAfterStartup >>> start");
        receiveInvitation();
        log.info("initializeAfterStartup <<< done");
    }

    public void handleMessage(Map<String, String> headers, String topic, String body) {
        String wallet = headers.get("Wallet");
        String state = JsonPath.read(body, "$.state");
        log.info("handleMessage >>> wallet:" + wallet + ", topic:" + topic + ", body:" + body);

        switch(topic) {
            case "connections":
                // When invitation is received, accept invitation
                if (state.equals("invitation")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> acceptInvitation");
                    acceptInvitation(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
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
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                    log.info("Alice demo is completed (Exit manually)");
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "basicmessages":
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print message");
                log.info("  - message:" + JsonPath.read(body, "$.content"));
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void receiveInvitation() {
        log.info("receiveInvitation >>>");
        String invitation = requestGET(faberContUrl + "/invitation", "");
        log.info("invitation:" + invitation);
        String response = requestPOST(adminUrl + "/connections/receive-invitation-with-endpoint", walletName, invitation);
        log.info("receiveInvitation <<<");
    }

    public void acceptInvitation(String connectionId) {
        String response = requestPOST(adminUrl + "/connections/" + connectionId + "/accept-invitation-with-endpoint", walletName, "{}");
    }

    public void sendCredentialRequest(String credExId) {
        String response = requestPOST(adminUrl + "/issue-credential/records/" + credExId + "/send-request", walletName, "{}");
    }

    public void sendProof(String reqBody) {
        String presExId = JsonPath.read(reqBody, "$.presentation_exchange_id");
        String response = requestGET(adminUrl + "/present-proof/records/" + presExId + "/credentials", walletName);

        ArrayList<LinkedHashMap<String, Object>> credentials = JsonPath.read(response, "$");
        int credRevId = 0;
        String credId = null;
        for (LinkedHashMap<String, Object> element : credentials) {
            int curCredRevId = Integer.parseInt(JsonPath.read(element, "$.cred_info.cred_rev_id"));
            if (curCredRevId > credRevId) {
                credRevId = curCredRevId;
                credId = JsonPath.read(element, "$.cred_info.referent");
            }
        }
        log.info("Use latest credential in demo - credRevId:" + credRevId + ", credId:"+ credId);

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

        response = requestPOST(adminUrl + "/present-proof/records/" + presExId + "/send-presentation", walletName, body);
    }

}