package com.sktelecom.ston.controller.alice;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    // agent configurations
    final String agentApiUrl = "http://localhost:8021";
    final String adminWalletName = "admin"; // admin wallet name when agent starts

    // controller configurations
    final String webhookUrl = "http://localhost:8023/webhooks"; // url to receive webhook messages
    final String version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99); // for randomness
    final String walletName = "alice." + version; // new walletName
    final String seed = UUID.randomUUID().toString().replaceAll("-", ""); // random seed 32 characters
    String did; // did
    String verkey; // verification key

    // faber configuration
    final String faberContUrl = "http://localhost:8022";

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("Create wallet and did, and register webhook url");
        createWalletAndDid();
        registerWebhookUrl();

        log.info("Configuration of faber:");
        log.info("- wallet name: " + walletName);
        log.info("- seed: " + seed);
        log.info("- did: " + did);
        log.info("- verification key: " + verkey);
        log.info("- webhook url: " + webhookUrl);

        log.info("Receive invitation from faber controller");
        receiveInvitation();
    }

    public void handleMessage(String topic, String body) {
        log.info("handleMessage >>> topic:" + topic + ", body:" + body);

        String state = JsonPath.read(body, "$.state");
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

    public void createWalletAndDid() {
        log.info("createWalletAndDid >>>");

        String body = JsonPath.parse("{" +
                "  wallet_name: '" + walletName + "'," +
                "  wallet_key: '" + walletName + ".key'," +
                "  wallet_type: indy" +
                "}").jsonString();
        log.info("Create a new wallet:" + prettyJson(body));
        String response = requestPOST(agentApiUrl + "/wallet", adminWalletName, body);

        body = JsonPath.parse("{ seed: '" + seed + "'}").jsonString();
        log.info("Create a new local did:" + prettyJson(body));
        response = requestPOST(agentApiUrl + "/wallet/did/create", walletName, body);
        did = JsonPath.read(response, "$.result.did");
        verkey = JsonPath.read(response, "$.result.verkey");
        log.info("created did: " + did + ", verkey: " + verkey);

        log.info("createWalletAndDid <<<");
    }

    public void registerWebhookUrl() {
        log.info("registerWebhookUrl >>>");

        String body = JsonPath.parse("{ target_url: '" + webhookUrl + "' }").jsonString();
        log.info("Create a new webhook target:" + prettyJson(body));
        String response = requestPOST(agentApiUrl + "/webhooks", walletName, body);

        log.info("registerWebhookUrl <<<");
    }

    public void receiveInvitation() {
        log.info("receiveInvitation >>>");
        String invitation = requestGET(faberContUrl + "/invitation", "");
        log.info("invitation:" + invitation);
        String response = requestPOST(agentApiUrl + "/connections/receive-invitation", walletName, invitation);
        log.info("receiveInvitation <<<");
    }

    public void sendCredentialRequest(String credExId) {
        String response = requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", walletName, "{}");
    }

    public void sendProof(String reqBody) {
        String presExId = JsonPath.read(reqBody, "$.presentation_exchange_id");
        String response = requestGET(agentApiUrl + "/present-proof/records/" + presExId + "/credentials", walletName);

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

        response = requestPOST(agentApiUrl + "/present-proof/records/" + presExId + "/send-presentation", walletName, body);
    }

}