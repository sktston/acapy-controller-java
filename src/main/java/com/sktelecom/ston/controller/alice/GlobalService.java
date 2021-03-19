package com.sktelecom.ston.controller.alice;

import com.jayway.jsonpath.JsonPath;
import com.sktelecom.ston.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private final HttpClient client = new HttpClient();

    // agent configurations
    final String[] apiUrls = {"http://localhost:8021"};
    //final String[] apiUrls = {"http://localhost:8021", "http://localhost:8031"}; // with docker-compose-multi.yml

    // controller configurations
    @Value("${controllerUrl}")
    private String controllerUrl; // FIXME: adjust url in application-alice.properties

    String version; // for randomness
    String walletName; // new walletName
    String walletId; // new wallet id
    String jwtToken; // jwt token for wallet
    String imageUrl;
    String webhookUrl; // url to receive webhook messagess

    // faber configuration
    @Value("${faberControllerUrl}")
    private String faberControllerUrl; // FIXME: adjust url in application-alice.properties

    // time calc
    int testTime = 120; // seconds
    long startTime;
    long endTime;
    long proofRequestTime;
    long ackTime;
    AtomicInteger counter = new AtomicInteger();
    double totalLatency = 0L;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();

        log.info("Receive invitation from faber controller");
        receiveInvitation();
    }

    public void handleEvent(String topic, String body) {
        String state = topic.equals("problem_report") ? "" : JsonPath.read(body, "$.state");
        //log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposal");
                    sendCredentialProposal(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "issue_credential":
                if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    sendCredentialRequest(JsonPath.read(body, "$.credential_exchange_id"));
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                if (content.contains("PrivacyPolicyOffer")) {
                    startTime = System.currentTimeMillis();
                    counter.set(0);
                    proofRequestTime = System.currentTimeMillis();
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyOffer) -> sendPrivacyPolicyAgreed");
                    sendPrivacyPolicyAgreed(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "present_proof":
                if (state.equals("request_received")) {
                    //log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProof");
                    sendProof(body);
                }
                else if (state.equals("presentation_acked")) {
                    counter.incrementAndGet();
                    ackTime = System.currentTimeMillis();
                    long latency = ackTime - proofRequestTime;
                    totalLatency = totalLatency + latency;

                    long elapsed = ackTime - startTime;
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> counter:" + counter.get() + ", latency: " + latency);
                    if (elapsed >= testTime * 1000L) {
                        delayedExit();
                    }
                    else {
                        log.info("- elapsed time:" + elapsed/1000 + " is less than test time:" + testTime + "  -> sendPrivacyPolicyAgreed");
                        proofRequestTime = System.currentTimeMillis();
                        sendPrivacyPolicyAgreed(JsonPath.read(body, "$.connection_id"));
                    }
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
                break;
            case "revocation_registry":
            case "issuer_cred_rev":
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void provisionController() {
        log.info("Create wallet");
        version = getRandomInt(1, 100) + "." + getRandomInt(1, 100) + "." + getRandomInt(1, 100);
        walletName = "alice." + version;
        imageUrl = "https://identicon-api.herokuapp.com/" + walletName + "/300?format=png";
        webhookUrl = controllerUrl + "/webhooks";
        createWallet();

        log.info("Configuration of alice:");
        log.info("- wallet name: " + walletName);
        log.info("- webhook url: " + webhookUrl);
        log.info("- wallet ID: " + walletId);
        log.info("- jwt token: " + jwtToken);
    }

    public void createWallet() {
        String body = JsonPath.parse("{" +
                "  wallet_name: '" + walletName + "'," +
                "  wallet_key: '" + walletName + ".key'," +
                "  wallet_type: 'indy'," +
                "  label: '" + walletName + ".label'," +
                "  image_url: '" + imageUrl + "'," +
                "  wallet_webhook_urls: ['" + webhookUrl + "']" +
                "}").jsonString();
        log.info("Create a new wallet:" + prettyJson(body));
        String response = client.requestPOST(randomStr(apiUrls) + "/multitenancy/wallet", null, body);
        log.info("response:" + response);
        walletId = JsonPath.read(response, "$.settings.['wallet.id']");
        jwtToken = JsonPath.read(response, "$.token");
    }

    public void receiveInvitation() {
        String invitationUrl = client.requestGET(faberControllerUrl + "/invitation-url", "");
        log.info("invitation-url: " + invitationUrl);
        String invitation = parseInvitationUrl(invitationUrl);
        if (invitation == null) {
            log.warn("Invalid invitationUrl");
            return;
        }
        log.info("invitation: " + invitation);
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/receive-invitation", jwtToken, invitation);
    }

    public void sendCredentialProposal(String connectionId) {

        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                // uncomment below if you want to request specific credential definition id to faber
                //"  cred_def_id: 'TCXu9qcEoRYX9jWT6CBFAy:3:CL:1614837027:tag'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/send-proposal", jwtToken, body);
    }

    public void sendPrivacyPolicyAgreed(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyAgreed'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/" + connectionId + "/send-message", jwtToken, body);
    }

    public void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/records/" + credExId + "/send-request", jwtToken, "{}");
    }

    public void sendProof(String reqBody) {
        String presExId = JsonPath.read(reqBody, "$.presentation_exchange_id");
        String response = client.requestGET(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/credentials", jwtToken);

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
        //log.info("Use latest credential in demo - credRevId: " + credRevId + ", credId: "+ credId);

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

        response = client.requestPOST(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/send-presentation", jwtToken, body);
    }

    public void deleteWallet() {
        log.info("Delete my wallet - walletName: " + walletName + ", walletId: " + walletId);
        String response = client.requestPOST(randomStr(apiUrls) + "/multitenancy/wallet/" + walletId + "/remove", null, "{}");
    }

    public void delayedExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                log.info("Alice demo completes - Exit");
                endTime = System.currentTimeMillis();
                long elapsed = endTime - startTime;
                long transNum = counter.get();
                double elapsedSec = (double) elapsed / 1000L;
                double totalLatencySec = totalLatency/1000L;

                log.info("--------------------------");
                log.info("Number of transactions : " + transNum);
                log.info("Elapsed time (sec): " + elapsedSec);
                log.info("TPS: " + transNum/elapsedSec);
                log.info("--------------------------");
                log.info("Total latency (sec): " + totalLatencySec);
                log.info("Average latency (sec): " + totalLatencySec/transNum);
                System.exit(0);
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 10L);
    }
}