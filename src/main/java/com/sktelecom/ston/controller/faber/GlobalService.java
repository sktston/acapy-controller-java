package com.sktelecom.ston.controller.faber;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    // agent configurations
    final String[] apiUrls = {"http://localhost:8021"};
    //final String[] apiUrls = {"http://localhost:8021", "http://localhost:8031"}; // with docker-compose-multi.yml
    final String stewardSeed = "000000000000000000000000Steward1";

    // controller configurations
    @Value("${controllerUrl}")
    private String controllerUrl; // FIXME: adjust url in application-faber.properties

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

    // check options
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();
    }

    public String createInvitation() {
        String params = "?public=true";
        String response = requestPOST(randomStr(apiUrls) + "/connections/create-invitation" + params, jwtToken, "{}");
        String invitation = JsonPath.parse((LinkedHashMap)JsonPath.read(response, "$.invitation")).jsonString();
        log.info("createInvitation <<< invitation:" + invitation);
        return invitation;
    }

    public String createInvitationUrl() {
        String params = "?public=true";
        String response = requestPOST(randomStr(apiUrls) + "/connections/create-invitation" + params, jwtToken, "{}");
        String invitationUrl = JsonPath.read(response, "$.invitation_url");
        log.info("createInvitationUrl <<< invitationUrl:" + invitationUrl);
        return invitationUrl;
    }

    public void handleMessage(String topic, String body) {
        log.info("handleMessage >>> topic:" + topic + ", body:" + body);

        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        switch(topic) {
            case "connections":
                // When connection with alice is done, send credential offer
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPrivacyPolicyOffer");
                    sendPrivacyPolicyOffer(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "issue_credential":
                // When credential is issued and acked, send proof(presentation) request
                if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProofRequest");
                    if (enableRevoke) {
                        revokeCredential(JsonPath.read(body, "$.revoc_reg_id"), JsonPath.read(body, "$.revocation_id"));
                    }
                    sendProofRequest(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "present_proof":
                // When proof is verified, print the result
                if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print result");
                    printProofResult(body);
                }
                else {
                    log.info("- Case (topic:topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "basicmessages":
                String message = JsonPath.read(body, "$.content");
                if (message.equals("PrivacyPolicyAgreed")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOffer");
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print message");
                }
                log.info("  - message: " + JsonPath.read(body, "$.content"));
                break;
            case "revocation_registry":
            case "issuer_cred_rev":
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
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
        version = getRandomInt(1, 100) + "." + getRandomInt(1, 100) + "." + getRandomInt(1, 100);
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

        log.info("Create schema and credential definition");
        createSchema();
        createCredentialDefinition();

        log.info("Configuration of faber:");
        log.info("- wallet name: " + walletName);
        log.info("- seed: " + seed);
        log.info("- did: " + did);
        log.info("- verification key: " + verkey);
        log.info("- webhook url: " + webhookUrl);
        log.info("- wallet ID: " + walletId);
        log.info("- jwt token: " + jwtToken);
        log.info("- schema ID: " + schemaId);
        log.info("- credential definition ID: " + credDefId);

        log.info("Initialization is done.");
        log.info("Run alice now.");
    }

    public void createWalletAndDid() {
        String body = JsonPath.parse("{" +
                "  wallet_name: '" + walletName + "'," +
                "  wallet_key: '" + walletName + ".key'," +
                "  wallet_type: 'indy'," +
                "  label: '" + walletName + ".label'," +
                "  image_url: '" + imageUrl + "'," +
                "  wallet_webhook_urls: ['" + webhookUrl + "']" +
                "}").jsonString();
        log.info("Create a new wallet:" + prettyJson(body));
        String response = requestPOST(randomStr(apiUrls) + "/multitenancy/wallet", null, body);
        log.info("response:" + response);
        walletId = JsonPath.read(response, "$.settings.['wallet.id']");
        jwtToken = JsonPath.read(response, "$.token");

        body = JsonPath.parse("{ seed: '" + seed + "'}").jsonString();
        log.info("Create a new local did:" + prettyJson(body));
        response = requestPOST(randomStr(apiUrls) + "/wallet/did/create", jwtToken, body);
        log.info("response:" + response);
        did = JsonPath.read(response, "$.result.did");
        verkey = JsonPath.read(response, "$.result.verkey");
        log.info("created did: " + did + ", verkey: " + verkey);
    }

    public String obtainStewardJwtToken() {
        String stewardWallet = "steward";
        String params = "?wallet_name=" + stewardWallet;

        // check if steward wallet already exists
        String response = requestGET(randomStr(apiUrls) + "/multitenancy/wallets" + params, null);
        ArrayList<LinkedHashMap<String, Object>> wallets = JsonPath.read(response, "$.results");

        if (wallets.isEmpty()) {
            // stewardWallet not exists -> create stewardWallet and get jwt token
            String body = JsonPath.parse("{" +
                    "  wallet_name: '" + stewardWallet + "'," +
                    "  wallet_key: '" + stewardWallet + ".key'," +
                    "  wallet_type: 'indy'," +
                    "}").jsonString();
            log.info("Not found steward wallet - Create a new steward wallet:" + prettyJson(body));
            response = requestPOST(randomStr(apiUrls) + "/multitenancy/wallet", null, body);
            log.info("response:" + response);
            String jwtToken = JsonPath.read(response, "$.token");

            body = JsonPath.parse("{ seed: '" + stewardSeed + "'}").jsonString();
            log.info("Create a steward did:" + prettyJson(body));
            response = requestPOST(randomStr(apiUrls) + "/wallet/did/create", jwtToken, body);
            log.info("response:" + response);
            String did = JsonPath.read(response, "$.result.did");

            params = "?did=" + did;
            log.info("Assign the did to public: " + did);
            response = requestPOST(randomStr(apiUrls) + "/wallet/did/public" + params, jwtToken, "{}");
            log.info("response: " + response);

            return jwtToken;
        }
        else {
            // stewardWallet exists -> get and return jwt token
            LinkedHashMap<String, Object> element = wallets.get(0);
            String stewardWalletId = JsonPath.read(element, "$.wallet_id");

            log.info("Found steward wallet - Get jwt token with wallet id: " + stewardWalletId);
            response = requestPOST(randomStr(apiUrls) + "/multitenancy/wallet/" + stewardWalletId + "/token", null, "{}");
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
        String response = requestPOST(randomStr(apiUrls) + "/ledger/register-nym" + params, stewardJwtToken, "{}");
        log.info("response: " + response);

        params = "?did=" + did;
        log.info("Assign the did to public: " + did);
        response = requestPOST(randomStr(apiUrls) + "/wallet/did/public" + params, jwtToken, "{}");
        log.info("response: " + response);
    }

    public void createSchema() {
        String body = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'date', 'degree', 'age']" +
                "}").jsonString();
        log.info("Create a new schema on the ledger:" + prettyJson(body));
        String response = requestPOST(randomStr(apiUrls) + "/schemas", jwtToken, body);
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
        String response = requestPOST(randomStr(apiUrls) + "/credential-definitions", jwtToken, body);
        credDefId = JsonPath.read(response, "$.credential_definition_id");
    }

    public void sendPrivacyPolicyOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyOffer'," +
                "}").jsonString();
        String response = requestPOST(randomStr(apiUrls) + "/connections/" + connectionId + "/send-message", jwtToken, body);
    }

    public void sendCredentialOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  credential_preview: {" +
                "    @type: 'https://didcomm.org/issue-credential/1.0/credential-preview'," +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }" +
                "    ]" +
                "  }" +
                "}").jsonString();
        String response = requestPOST(randomStr(apiUrls) + "/issue-credential/send-offer", jwtToken, body);
    }

    public void sendProofRequest(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  proof_request: {" +
                "    name: 'proof_name'," +
                "    version: '1.0'," +
                "    requested_attributes: {" +
                "      attr_name: {" +
                "        name: 'name'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_date: {" +
                "        name: 'date'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_degree: {" +
                "        name: 'degree'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "      pred_age: {" +
                "        name: 'age'," +
                "        p_type: '>='," +
                "        p_value: 20," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = requestPOST(randomStr(apiUrls) + "/present-proof/send-request", jwtToken, body);
    }

    public void printProofResult(String body) {
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation.requested_proof")).jsonString();
        log.info("  - Proof requested:" + prettyJson(requestedProof));
        String verified = JsonPath.read(body, "$.verified");
        log.info("  - Proof validation:" + verified);
    }

    public void revokeCredential(String revRegId, String credRevId) {
        log.info("revokeCredential >>> revRegId:" + revRegId + ", credRevId:" + credRevId);
        String body = JsonPath.parse("{" +
                "  rev_reg_id: '" + revRegId + "'," +
                "  cred_rev_id: '" + credRevId + "'," +
                "  publish: true" +
                "}").jsonString();
        String response =  requestPOST(randomStr(apiUrls) + "/revocation/revoke", jwtToken, body);
        log.info("response: " + response);
    }

    public byte[] generateQRCode(String text, int width, int height) {

        Assert.hasText(text, "text must not be empty");
        Assert.isTrue(width > 0, "width must be greater than zero");
        Assert.isTrue(height > 0, "height must be greater than zero");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height);
            MatrixToImageWriter.writeToStream(matrix, MediaType.IMAGE_PNG.getSubtype(), outputStream, new MatrixToImageConfig());
        } catch (IOException | WriterException e) {
            e.printStackTrace();
        }
        return outputStream.toByteArray();
    }

}