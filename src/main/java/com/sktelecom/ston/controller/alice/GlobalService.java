package com.sktelecom.ston.controller.alice;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    // agent configurations
    final String[] apiUrls = {"http://localhost:8021"};
    //final String[] apiUrls = {"http://localhost:8021", "http://localhost:8031"}; // with docker-compose-multi.yml
    int iterations = 1; // for long-term test

    // controller configurations
    @Value("${controllerUrl}")
    private String controllerUrl; // FIXME: adjust url in application-alice.properties

    String version; // for randomness
    String walletName; // new walletName
    String imageUrl;
    String webhookUrl; // url to receive webhook messagess

    // faber configuration
    @Value("${faberControllerUrl}")
    private String faberControllerUrl; // FIXME: adjust url in application-alice.properties

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();

        log.info("Receive invitation from faber controller");
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
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> deleteWalletAndExit");
                    deleteWalletAndExit();
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

    public void provisionController() {
        log.info("Create wallet and did, and register webhook url");
        version = getRandomInt(1, 100) + "." + getRandomInt(1, 100) + "." + getRandomInt(1, 100);
        walletName = "alice." + version;
        imageUrl = "https://identicon-api.herokuapp.com/" + walletName + "/300?format=png";
        webhookUrl = controllerUrl + "/webhooks";
        createWallet();

        log.info("Configuration of alice:");
        log.info("- wallet name: " + walletName);
        log.info("- webhook url: " + webhookUrl);
    }

    public void createWallet() {
        String body = JsonPath.parse("{" +
                "  name: '" + walletName + "'," +
                "  key: '" + walletName + ".key'," +
                "  type: 'indy'," +
                "  label: '" + walletName + ".label'," +
                "  image_url: '" + imageUrl + "'," +
                "  webhook_urls: ['" + webhookUrl + "']" +
                "}").jsonString();
        log.info("Create a new wallet:" + prettyJson(body));
        String response = requestPOST(randomStr(apiUrls) + "/wallet", "", body);
        log.info("response:" + prettyJson(response));
    }

    public void receiveInvitation() {
        String invitation = requestGET(faberControllerUrl + "/invitation", "");
        log.info("invitation:" + invitation);
        String response = requestPOST(randomStr(apiUrls) + "/connections/receive-invitation", walletName, invitation);
    }

    public void sendCredentialRequest(String credExId) {
        String response = requestPOST(randomStr(apiUrls) + "/issue-credential/records/" + credExId + "/send-request", walletName, "{}");
    }

    public void sendProof(String reqBody) {
        String presExId = JsonPath.read(reqBody, "$.presentation_exchange_id");
        String response = requestGET(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/credentials", walletName);

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

        response = requestPOST(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/send-presentation", walletName, body);
    }

    public void deleteWallet() {
        log.info("Delete my wallet - walletName: " + walletName);
        String response = requestDELETE(randomStr(apiUrls) + "/wallet/me", walletName);
    }

    public void deleteWalletAndExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                deleteWallet();
                if (--iterations == 0) {
                    log.info("Alice demo completes - Exit");
                    System.exit(0);
                }
                else {
                    log.info("Remaining iterations : " + iterations);
                    provisionController();

                    log.info("Receive invitation from faber controller");
                    receiveInvitation();
                }
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 100L);
    }
}