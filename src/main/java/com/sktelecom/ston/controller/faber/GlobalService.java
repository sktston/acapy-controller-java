package com.sktelecom.ston.controller.faber;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sktelecom.ston.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Value;
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
    private final HttpClient client = new HttpClient();

    final String stewardSeed = "000000000000000000000000Steward1";

    @Value("${apiUrlList}")
    private String apiUrlList;

    // controller configurations
    @Value("${controllerUrl}")
    private String controllerUrl; // FIXME: adjust url in application-faber.properties

    @Value("${walletType}")
    private String walletType;

    String[] apiUrls;
    String version; // for randomness
    String walletName; // new wallet name
    String walletId; // new wallet id
    String jwtToken; // jwt token for wallet
    String stewardJwtToken; // // jwt token for steward to register nym
    String imageUrl;
    String seed; // random seed 32 characters
    String webhookUrl; // url to receive webhook messagess
    String did; // did
    String verkey; // verification key
    String schemaId; // schema identifier
    String credDefId; // credential definition identifier
    String photoFileName = "images/ci_t.jpg"; // sample image file

    // check options
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));
    static boolean useMultitenancy = Boolean.parseBoolean(System.getenv().getOrDefault("USE_MULTITENANCY", "true"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        apiUrls = apiUrlList.split(",");
        provisionController();
    }

    public String createInvitationUrl() {
        String params = "?public=true";
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/create-invitation" + params, jwtToken, "{}");
        String invitationUrl = JsonPath.read(response, "$.invitation_url");
        log.info("createInvitationUrl <<< invitationUrl:" + invitationUrl);
        return invitationUrl;
    }

    public void handleEvent(String topic, String body) {
        String state = null;
        try {
            state = JsonPath.read(body, "$.state");
        } catch (PathNotFoundException e) {}
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                break;
            case "issue_credential":
                if (state.equals("proposal_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOffer");
                    String credentialProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential_proposal_dict")).jsonString();
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"), credentialProposal);
                }
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPrivacyPolicyOffer");
                    if (enableRevoke) {
                        revokeCredential(JsonPath.read(body, "$.credential_exchange_id"));
                    }
                    sendPrivacyPolicyOffer(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "issue_credential_v2_0":
                if (state.equals("proposal-received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOfferV2");
                    String credProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.cred_proposal")).jsonString();
                    sendCredentialOfferV2(JsonPath.read(body, "$.connection_id"), credProposal);
                }
                else if (state.equals("done")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPrivacyPolicyOfferV2");
                    if (enableRevoke) {
                        revokeCredential(JsonPath.read(body, "$.cred_ex_id"));
                    }
                    sendPrivacyPolicyOfferV2(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                if (content.contains("PrivacyPolicyAgreedV2")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyAgreed) -> sendProofRequestV2");
                    sendProofRequestV2(JsonPath.read(body, "$.connection_id"));
                }
                else if (content.contains("PrivacyPolicyAgreed")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyAgreed) -> sendProofRequest");
                    sendProofRequest(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "present_proof":
                if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> printProofResult");
                    String presRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                    String presentation = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation")).jsonString();
                    printProofResult(JsonPath.read(body, "$.verified"), presRequest, presentation);
                }
                break;
            case "present_proof_v2_0":
                if (state.equals("done")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> printProofResultV2");
                    String presReq = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.pres_request")).jsonString();
                    String pres = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.pres")).jsonString();
                    printProofResultV2(JsonPath.read(body, "$.verified"), presReq, pres);
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
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

        if (useMultitenancy) {
            walletName = "faber." + version;
            imageUrl = "https://identicon-api.herokuapp.com/" + walletName + "/300?format=png";
            seed = UUID.randomUUID().toString().replaceAll("-", "");
            webhookUrl = controllerUrl + "/webhooks";

            log.info("Obtain jwtToken of steward");
            stewardJwtToken = obtainStewardJwtToken();

            log.info("Create wallet and did");
            createWalletAndDid();
            log.info("Register did as issuer");
            registerDidAsIssuer();
        }
        else {
            updateEndpoint();
        }

        log.info("Create schema and credential definition");
        createSchema();
        createCredentialDefinition();

        log.info("Configuration of faber:");
        if (useMultitenancy) {
            log.info("- wallet name: " + walletName);
            log.info("- seed: " + seed);
            log.info("- webhook url: " + webhookUrl);
            log.info("- wallet ID: " + walletId);
            log.info("- wallet type: " + walletType);
            log.info("- jwt token: " + jwtToken);
        }
        log.info("- did: " + did);
        log.info("- verification key: " + verkey);
        log.info("- schema ID: " + schemaId);
        log.info("- credential definition ID: " + credDefId);

        log.info("Initialization is done.");
        log.info("Run alice now.");
    }

    public void createWalletAndDid() {
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

        body = JsonPath.parse("{ seed: '" + seed + "'}").jsonString();
        log.info("Create a new local did:" + prettyJson(body));
        response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/create", jwtToken, body);
        log.info("response:" + response);
        did = JsonPath.read(response, "$.result.did");
        verkey = JsonPath.read(response, "$.result.verkey");
        log.info("created did: " + did + ", verkey: " + verkey);
    }

    public String obtainStewardJwtToken() {
        String stewardWallet = "steward";
        String params = "?wallet_name=" + stewardWallet;

        // check if steward wallet already exists
        String response = client.requestGET(randomStr(apiUrls) + "/multitenancy/wallets" + params, null);
        ArrayList<LinkedHashMap<String, Object>> wallets = JsonPath.read(response, "$.results");

        if (wallets.isEmpty()) {
            // stewardWallet not exists -> create stewardWallet and get jwt token
            String body = JsonPath.parse("{" +
                    "  wallet_name: '" + stewardWallet + "'," +
                    "  wallet_key: '" + stewardWallet + ".key'," +
                    "  wallet_type: '" + walletType + "'," +
                    "}").jsonString();
            log.info("Not found steward wallet - Create a new steward wallet:" + prettyJson(body));
            response = client.requestPOST(randomStr(apiUrls) + "/multitenancy/wallet", null, body);
            log.info("response:" + response);
            String jwtToken = JsonPath.read(response, "$.token");

            body = JsonPath.parse("{ seed: '" + stewardSeed + "'}").jsonString();
            log.info("Create a steward did:" + prettyJson(body));
            response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/create", jwtToken, body);
            log.info("response:" + response);
            String did = JsonPath.read(response, "$.result.did");

            params = "?did=" + did;
            log.info("Assign the did to public: " + did);
            response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/public" + params, jwtToken, "{}");
            log.info("response: " + response);

            return jwtToken;
        }
        else {
            // stewardWallet exists -> get and return jwt token
            LinkedHashMap<String, Object> element = wallets.get(0);
            String stewardWalletId = JsonPath.read(element, "$.wallet_id");

            log.info("Found steward wallet - Get jwt token with wallet id: " + stewardWalletId);
            response = client.requestPOST(randomStr(apiUrls) + "/multitenancy/wallet/" + stewardWalletId + "/token", null, "{}");
            log.info("response: " + response);

            return JsonPath.read(response, "$.token");
        }
    }

    public void registerDidAsIssuer() {
        String params = "?did=" + did +
                "&verkey=" + verkey +
                "&alias=" + walletName +
                "&role=ENDORSER";
        log.info("Register the did to the ledger as a ENDORSER");
        String response = client.requestPOST(randomStr(apiUrls) + "/ledger/register-nym" + params, stewardJwtToken, "{}");
        log.info("response: " + response);

        params = "?did=" + did;
        log.info("Assign the did to public: " + did);
        response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/public" + params, jwtToken, "{}");
        log.info("response: " + response);
    }

    public void updateEndpoint() {
        String response = client.requestGET(randomStr(apiUrls) + "/wallet/did/public", jwtToken);
        log.info("response: " + response);
        did = JsonPath.read(response, "$.result.did");
        verkey = JsonPath.read(response, "$.result.verkey");

        String params = "?did=" + did;
        response = client.requestGET(randomStr(apiUrls) + "/wallet/get-did-endpoint" + params, jwtToken);
        log.info("response: " + response);
        String endpoint =  JsonPath.read(response, "$.endpoint");

        String body = JsonPath.parse("{" +
                "  did: '" + did + "'," +
                "  endpoint: '" + endpoint + "'," +
                "  endpoint_type: 'Endpoint'," +
                "}").jsonString();
        log.info("Update endpoint: " + body);
        response = client.requestPOST(randomStr(apiUrls) + "/wallet/set-did-endpoint", jwtToken, body);
        log.info("response: " + response);
    }

    public void createSchema() {
        String body = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'date', 'degree', 'age', 'photo']" +
                "}").jsonString();
        log.info("Create a new schema on the ledger:" + prettyJson(body));
        String response = client.requestPOST(randomStr(apiUrls) + "/schemas", jwtToken, body);
        schemaId = JsonPath.read(response, "$.schema_id");
    }

    public void createCredentialDefinition() {
        String body = JsonPath.parse("{" +
                "  schema_id: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  support_revocation: true," +
                "  revocation_registry_size: 10" +
                "}").jsonString();
        log.info("Create a new credential definition on the ledger:" + prettyJson(body));
        String response = client.requestPOST(randomStr(apiUrls) + "/credential-definitions", jwtToken, body);
        credDefId = JsonPath.read(response, "$.credential_definition_id");
    }

    public void sendCredentialOffer(String connectionId, String credentialProposal) {
        // uncomment below if you want to get requested credential definition id from alice
        //String requestedCredDefId = JsonPath.read(credentialProposal, "$.cred_def_id");

        String encodedImage = "";
        try {
            encodedImage = encodeFileToBase64Binary(photoFileName);
        } catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  credential_proposal: {" +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }," +
                "      { name: 'photo', value: '" + encodedImage + "', mime-type: 'image/jpeg' }" +
                "    ]" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/send", jwtToken, body);
    }

    public void sendCredentialOfferV2(String connectionId, String credProposal) {
        // uncomment below if you want to get requested credential definition id from alice
        //String encodedData = JsonPath.read(credProposal, "$.filters~attach.[0].data.base64");
        //String filter = new String(Base64.decodeBase64(encodedData));
        //String requestedCredDefId = JsonPath.read(filter, "$.cred_def_id");

        String encodedImage = "base64EncodedJpegImage";
        // uncomment below if you want to use actual encoded jpeg image
        //try {
        //    encodedImage = encodeFileToBase64Binary(photoFileName);
        //} catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  credential_preview: {" +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }," +
                "      { name: 'photo', value: '" + encodedImage + "', mime-type: 'image/jpeg' }" +
                "    ]" +
                "  }," +
                "  filter: {" +
                "    indy: {" +
                "      cred_def_id: '" + credDefId + "'," +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/send", jwtToken, body);
    }

    public void sendPrivacyPolicyOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyOffer. Content here. If you agree, send me a message. PrivacyPolicyAgreed'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/" + connectionId + "/send-message", jwtToken, body);
    }

    public void sendPrivacyPolicyOfferV2(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyOfferV2. Content here. If you agree, send me a message. PrivacyPolicyAgreedV2'," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/" + connectionId + "/send-message", jwtToken, body);
    }

    public void sendProofRequest(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  proof_request: {" +
                "    name: 'proof_name'," +
                "    version: '1.0'," +
                "    requested_attributes: {" +
                "      name: {" +
                "        name: 'name'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      date: {" +
                "        name: 'date'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      degree: {" +
                "        name: 'degree'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      photo: {" +
                "        name: 'photo'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "      age: {" +
                "        name: 'age'," +
                "        p_type: '>='," +
                "        p_value: 20," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof/send-request", jwtToken, body);
    }

    public void sendProofRequestV2(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  presentation_request: {" +
                "    indy: {" +
                "      name: 'proof_name'," +
                "      version: '1.0'," +
                "      requested_attributes: {" +
                "        name: {" +
                "          name: 'name'," +
                "          non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "          restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "        }," +
                "        date: {" +
                "          name: 'date'," +
                "          non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "          restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "        }," +
                "        degree: {" +
                "          name: 'degree'," +
                "          non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "          restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "        }," +
                "        photo: {" +
                "          name: 'photo'," +
                "          non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "          restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "        }" +
                "      }," +
                "      requested_predicates: {" +
                "        age: {" +
                "          name: 'age'," +
                "          p_type: '>='," +
                "          p_value: 20," +
                "          non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "          restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof-2.0/send-request", jwtToken, body);
    }

    public void printProofResult(String verified, String presRequest, String presentation) {
        if (!verified.equals("true")) {
            log.info("proof is not verified");
            return;
        }

        LinkedHashMap<String, Object> requestedAttrs = JsonPath.read(presRequest, "$.requested_attributes");
        LinkedHashMap<String, Object> revealedAttrs = JsonPath.read(presentation, "$.requested_proof.revealed_attrs");
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        for(String key : requestedAttrs.keySet()) {
            String name = JsonPath.read(requestedAttrs.get(key), "$.name");
            String value = "unrevealed";
            if (revealedAttrs.containsKey(key))
                value = JsonPath.read(revealedAttrs.get(key), "$.raw");
            attrs.put(name, value);
        }

        LinkedHashMap<String, Object> requestedPreds = JsonPath.read(presRequest, "$.requested_predicates");
        LinkedHashMap<String, String> preds = new LinkedHashMap<>();
        for(String key : requestedPreds.keySet()) {
            String name = JsonPath.read(requestedPreds.get(key), "$.name");
            String type = JsonPath.read(requestedPreds.get(key), "$.p_type");
            int value = JsonPath.read(requestedPreds.get(key), "$.p_value");
            preds.put(name, type + " " + value);
        }

        log.info("Proof is verified");
        for(String key : attrs.keySet())
            log.info("Requested Attribute - " + key + ": " + attrs.get(key));
        for(String key : preds.keySet())
            log.info("Requested Predicate - " + key + " " + preds.get(key));
    }

    public void printProofResultV2(String verified, String presReq, String pres) {
        String encodedData = JsonPath.read(presReq, "$.request_presentations~attach.[0].data.base64");
        String presRequest = new String(Base64.decodeBase64(encodedData));
        encodedData = JsonPath.read(pres, "$.presentations~attach.[0].data.base64");
        String presentation = new String(Base64.decodeBase64(encodedData));

        printProofResult(verified, presRequest, presentation);
    }

    public void revokeCredential(String credExId) {
        log.info("revokeCredential >>> credExId:" + credExId );
        String body = JsonPath.parse("{" +
                "  cred_ex_id: '" + credExId + "'," +
                "  publish: true" +
                "}").jsonString();
        String response =  client.requestPOST(randomStr(apiUrls) + "/revocation/revoke", jwtToken, body);
        log.info("response: " + response);
    }

}