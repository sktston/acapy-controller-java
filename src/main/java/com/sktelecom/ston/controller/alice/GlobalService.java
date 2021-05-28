package com.sktelecom.ston.controller.alice;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sktelecom.ston.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import org.apache.commons.codec.binary.Base64;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Timer;
import java.util.TimerTask;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private final HttpClient client = new HttpClient();

    int iterations = 1; // for long-term test

    @Value("${apiUrlList}")
    private String apiUrlList;

    @Value("${controllerUrl}")
    private String controllerUrl; // FIXME: adjust url in application-alice.properties

    @Value("${walletType}")
    private String walletType;

    @Value("${protocolVersion}")
    private String protocolVersion;

    String[] apiUrls;
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
    long beforeTime;
    long afterTime;

    // check options
    static boolean useMultitenancy = Boolean.parseBoolean(System.getenv().getOrDefault("USE_MULTITENANCY", "true"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        apiUrls = apiUrlList.split(",");
        beforeTime = System.currentTimeMillis();
        if (useMultitenancy)
            provisionController();

        log.info("- protocol version: " + protocolVersion);
        log.info("Receive invitation from faber controller");
        receiveInvitation();
    }

    public void handleEvent(String topic, String body) {
        String state = null;
        try {
            state = JsonPath.read(body, "$.state");
        } catch (PathNotFoundException e) {}
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                if (state.equals("active")) {
                    if (protocolVersion.equals("2.0")) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposalV2");
                        String connectionId = JsonPath.read(body, "$.connection_id");
                        sendCredentialProposalV2(connectionId);
                    }
                    else {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposal");
                        String connectionId = JsonPath.read(body, "$.connection_id");
                        sendCredentialProposal(connectionId);
                    }
                }
                break;
            case "issue_credential":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> PrintBody");
                    log.warn("  - body:" + body);
                }
                else if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    String credExId = JsonPath.read(body, "$.credential_exchange_id");
                    sendCredentialRequest(credExId);
                }
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPresentationProposal");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendPresentationProposal(connectionId);
                }
                break;
            case "issue_credential_v2_0":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> PrintBody");
                    log.warn("  - body:" + body);
                }
                else if (state.equals("offer-received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequestV2");
                    String credExId = JsonPath.read(body, "$.cred_ex_id");
                    sendCredentialRequestV2(credExId);
                }
                else if (state.equals("done")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPresentationProposaV2");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendPresentationProposalV2(connectionId);
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                if (content.contains("PrivacyPolicyOfferV2")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyOffer) -> sendPrivacyPolicyAgreedV2");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendPrivacyPolicyAgreedV2(connectionId);
                }
                else if (content.contains("PrivacyPolicyOffer")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyOffer) -> sendPrivacyPolicyAgreed");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendPrivacyPolicyAgreed(connectionId);
                }
                break;
            case "present_proof":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> PrintBody");
                    log.warn("  - body:" + body);
                }
                else if (state.equals("request_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProof");
                    String presExId = JsonPath.read(body, "$.presentation_exchange_id");
                    String presentationRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                    sendProof(presExId, presentationRequest);
                }
                else if (state.equals("presentation_acked")) {
                    if (useMultitenancy) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> deleteWalletAndExit");
                        deleteWalletAndExit();
                    }
                    else {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> delayedExit");
                        delayedExit();
                    }
                }
                break;
            case "present_proof_v2_0":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> PrintBody");
                    log.warn("  - body:" + body);
                }
                else if (state.equals("request-received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProofV2");
                    String presRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.pres_request")).jsonString();
                    sendProofV2(JsonPath.read(body, "$.pres_ex_id"), presRequest);
                }
                else if (state.equals("done")) {
                    if (useMultitenancy) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> deleteWalletAndExit");
                        deleteWalletAndExit();
                    }
                    else {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> delayedExit");
                        delayedExit();
                    }
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + body);
                break;
            case "revocation_registry":
            case "issuer_cred_rev":
            case "issue_credential_v2_0_indy":
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void provisionController() {
        version = getRandomInt(1, 100) + "." + getRandomInt(1, 100) + "." + getRandomInt(1, 100);
        walletName = "alice." + version;
        imageUrl = "https://identicon-api.herokuapp.com/" + walletName + "/300?format=png";
        webhookUrl = controllerUrl + "/webhooks";

        log.info("Create wallet");
        createWallet();

        log.info("Configuration of alice:");
        log.info("- wallet name: " + walletName);
        log.info("- webhook url: " + webhookUrl);
        log.info("- wallet ID: " + walletId);
        log.info("- wallet type: " + walletType);
        log.info("- jwt token: " + jwtToken);
    }

    public void createWallet() {
        String body = JsonPath.parse("{" +
                "  wallet_name: '" + walletName + "'," +
                "  wallet_key: '" + walletName + ".key'," +
                "  wallet_type: '" + walletType + "'," +
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
        log.info("response: " + response);
    }

    public void sendCredentialProposal(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                // uncomment below if you want to request specific credential definition id to faber
                //"  cred_def_id: 'TCXu9qcEoRYX9jWT6CBFAy:3:CL:1614837027:tag'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/send-proposal", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendCredentialProposalV2(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  filter: { indy: {} }," +
                // uncomment below if you want to request specific credential definition id to faber
                //"  filter: { indy: { cred_def_id: 'TCXu9qcEoRYX9jWT6CBFAy:3:CL:1614837027:tag' } }," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/send-proposal", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendPresentationProposal(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  presentation_proposal: {" +
                "    attributes: []," +
                "    predicates: []" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof/send-proposal", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendPresentationProposalV2(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  presentation_proposal: {}" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof/send-proposal", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendPrivacyPolicyAgreed(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyAgreed'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/" + connectionId + "/send-message", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendPrivacyPolicyAgreedV2(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyAgreedV2'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/" + connectionId + "/send-message", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/records/" + credExId + "/send-request", jwtToken, "{}");
        log.info("response: " + response);
    }

    public void sendCredentialRequestV2(String credExId) {
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/records/" + credExId + "/send-request", jwtToken, "{}");
        log.info("response: " + response);
    }

    public void sendProof(String presExId, String presentationRequest) {
        String response = client.requestGET(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/credentials", jwtToken);
        log.info("Matching Credentials in my wallet: " + response);

        ArrayList<LinkedHashMap<String, Object>> credentials = JsonPath.read(response, "$");
        int credRevId = 0;
        String credId = null;
        for (LinkedHashMap<String, Object> element : credentials) {
            if (JsonPath.read(element, "$.cred_info.cred_rev_id") != null){ // case of support revocation
                // for adjusting the type of cred_rev_id (libindy: String and askar: int)
                Object objCredRevId = JsonPath.read(element, "$.cred_info.cred_rev_id");
                int curCredRevId;
                if (objCredRevId instanceof String)
                    curCredRevId = Integer.parseInt((String) objCredRevId);
                else
                    curCredRevId = (Integer) objCredRevId;

                if (curCredRevId > credRevId) {
                    credRevId = curCredRevId;
                    credId = JsonPath.read(element, "$.cred_info.referent");
                }
            }
            else { // case of not support revocation
                credId = JsonPath.read(element, "$.cred_info.referent");
            }
        }
        log.info("Use latest credential in demo - credId: "+ credId);

        // Make body using presentationRequest
        LinkedHashMap<String, Object> reqAttrs = JsonPath.read(presentationRequest, "$.requested_attributes");
        for(String key : reqAttrs.keySet())
            reqAttrs.replace(key, JsonPath.parse("{ cred_id: '" + credId + "', revealed: true }").json());

        LinkedHashMap<String, Object> reqPreds = JsonPath.read(presentationRequest, "$.requested_predicates");
        for(String key : reqPreds.keySet())
            reqPreds.replace(key, JsonPath.parse("{ cred_id: '" + credId + "' }").json());

        LinkedHashMap<String, Object> selfAttrs = new LinkedHashMap<>();

        String body = JsonPath.parse("{}").put("$", "requested_attributes", reqAttrs)
                .put("$", "requested_predicates", reqPreds)
                .put("$", "self_attested_attributes", selfAttrs).jsonString();

        response = client.requestPOST(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/send-presentation", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendProofV2(String presExId, String presRequest) {
        String response = client.requestGET(randomStr(apiUrls) + "/present-proof-2.0/records/" + presExId + "/credentials", jwtToken);
        log.info("Matching Credentials in my wallet: " + response);

        ArrayList<LinkedHashMap<String, Object>> credentials = JsonPath.read(response, "$");
        int credRevId = 0;
        String credId = null;
        for (LinkedHashMap<String, Object> element : credentials) {
            if (JsonPath.read(element, "$.cred_info.cred_rev_id") != null){ // case of support revocation
                // for adjusting the type of cred_rev_id (libindy: String and askar: int)
                Object objCredRevId = JsonPath.read(element, "$.cred_info.cred_rev_id");
                int curCredRevId;
                if (objCredRevId instanceof String)
                    curCredRevId = Integer.parseInt((String) objCredRevId);
                else
                    curCredRevId = (Integer) objCredRevId;

                if (curCredRevId > credRevId) {
                    credRevId = curCredRevId;
                    credId = JsonPath.read(element, "$.cred_info.referent");
                }
            }
            else { // case of not support revocation
                credId = JsonPath.read(element, "$.cred_info.referent");
            }
        }
        log.info("Use latest credential in demo - credId: "+ credId);

        String encodedData = JsonPath.read(presRequest, "$.request_presentations~attach.[0].data.base64");
        String requestPresentation = new String(Base64.decodeBase64(encodedData));

        // Make body using requestPresentation
        LinkedHashMap<String, Object> reqAttrs = JsonPath.read(requestPresentation, "$.requested_attributes");
        for(String key : reqAttrs.keySet())
            reqAttrs.replace(key, JsonPath.parse("{ cred_id: '" + credId + "', revealed: true }").json());

        LinkedHashMap<String, Object> reqPreds = JsonPath.read(requestPresentation, "$.requested_predicates");
        for(String key : reqPreds.keySet())
            reqPreds.replace(key, JsonPath.parse("{ cred_id: '" + credId + "' }").json());

        LinkedHashMap<String, Object> selfAttrs = new LinkedHashMap<>();

        String presentation = JsonPath.parse("{}").put("$", "requested_attributes", reqAttrs)
                .put("$", "requested_predicates", reqPreds)
                .put("$", "self_attested_attributes", selfAttrs).jsonString();
        String body =  JsonPath.parse("{" +
                "  indy: " + presentation +
                "}").jsonString();

        response = client.requestPOST(randomStr(apiUrls) + "/present-proof-2.0/records/" + presExId + "/send-presentation", jwtToken, body);
        log.info("response: " + response);
    }

    public void deleteWallet() {
        log.info("Delete my wallet - walletName: " + walletName + ", walletId: " + walletId);
        String response = client.requestPOST(randomStr(apiUrls) + "/multitenancy/wallet/" + walletId + "/remove", null, "{}");
        log.info("response: " + response);
    }

    public void deleteWalletAndExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                deleteWallet();
                if (--iterations == 0) {
                    log.info("Alice demo completes - Exit");
                    afterTime = System.currentTimeMillis();
                    long secDiffTime = afterTime - beforeTime;
                    log.info("Elapsed time (ms) : " + secDiffTime);
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

    public void delayedExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                if (--iterations == 0) {
                    log.info("Alice demo completes - Exit");
                    afterTime = System.currentTimeMillis();
                    long secDiffTime = afterTime - beforeTime;
                    log.info("Elapsed time (ms) : " + secDiffTime);
                    System.exit(0);
                }
                else {
                    log.info("Remaining iterations : " + iterations);
                    log.info("Receive invitation from faber controller");
                    receiveInvitation();
                }
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 100L);
    }
}