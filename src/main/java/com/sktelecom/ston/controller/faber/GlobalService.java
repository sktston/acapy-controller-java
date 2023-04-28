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
import java.util.Locale;
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
    String keyDid; // key method did

    // check options
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));
    static boolean useMultitenancy = Boolean.parseBoolean(System.getenv().getOrDefault("USE_MULTITENANCY", "true"));
    static boolean enableCredProblem = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_CRED_PROBLEM", "false"));
    static boolean enablePresentProblem = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_PRESENT_PROBLEM", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        apiUrls = apiUrlList.split(",");
        provisionController();
    }

    public String createInvitationUrl() {
        String params = "?public=true";
        String response = client.requestPOST(randomStr(apiUrls) + "/connections/create-invitation" + params, jwtToken, "{}");
        log.info("response: " + response);
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
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("proposal_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> checkCredentialProposal && sendCredentialOffer");
                    String credExId = JsonPath.read(body, "$.credential_exchange_id");
                    String credentialProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential_proposal_dict")).jsonString();
                    if (checkCredentialProposal(credExId, credentialProposal))
                        sendCredentialOffer(credExId);
                }
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> issue credential successfully");
                    String credExId = JsonPath.read(body, "$.credential_exchange_id");
                    if (enableRevoke)
                        revokeCredential(credExId);
                }
                break;
            case "issue_credential_v2_0":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("proposal-received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> checkCredentialProposalV2 && sendCredentialOfferV2");
                    String credExId = JsonPath.read(body, "$.cred_ex_id");
                    String credProposal = JsonPath.parse((LinkedHashMap) JsonPath.read(body, "$.cred_proposal")).jsonString();
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    String credFormat = JsonPath.read(body, "$.cred_proposal.formats.[0].attach_id");
                    if (credFormat.equals("indy")){
                        if (checkCredentialProposalV2(credExId, credProposal))
                            sendCredentialOfferV2WithNew(connectionId);
                            //sendCredentialOfferV2(credExId);
                    }
                    else if (credFormat.equals("ld_proof")) {
                        if (checkCredentialProposalV2JsonLd(credExId, credProposal))
                            sendCredentialOfferV2WithNewJsonLd(connectionId);
                            //sendCredentialOfferV2(credExId);
                    }
                    else
                        log.warn("credFormat is not indy or ld_proof");
                }
                else if (state.equals("done")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> issue credential successfully");
                    String credExId = JsonPath.read(body, "$.cred_ex_id");
                    if (enableRevoke)
                        revokeCredential(credExId);
                }
                break;
            case "present_proof":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("proposal_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> checkPresentationProposal && sendProofRequest");
                    String presExId = JsonPath.read(body, "$.presentation_exchange_id");
                    String presentationProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_proposal_dict")).jsonString();
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    if (checkPresentationProposal(presExId, presentationProposal))
                        sendProofRequest(connectionId);
                }
                else if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> printProofResult");
                    String presRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                    String presentation = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation")).jsonString();
                    String verified = JsonPath.read(body, "$.verified");
                    printProofResult(verified, presRequest, presentation);
                }
                break;
            case "present_proof_v2_0":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("proposal-received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> checkPresentationProposalV2 && sendProofRequestV2");
                    String presExId = JsonPath.read(body, "$.pres_ex_id");
                    String presProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.pres_proposal")).jsonString();
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    String presFormat = getPresFormat(presProposal);
                    if (presFormat.equals("indy")) {
                        if (checkPresentationProposalV2(presExId, presProposal))
                            sendProofRequestV2(connectionId);
                    }
                    else if (presFormat.equals("dif")) {
                        if (checkPresentationProposalV2JsonLd(presExId, presProposal))
                            sendProofRequestV2JsonLd(connectionId);
                    }
                    else
                        log.warn("presFormat is not indy or dif");
                }
                else if (state.equals("done")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> printProofResultV2");
                    String presReq = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.pres_request")).jsonString();
                    String pres = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.pres")).jsonString();
                    String verified = JsonPath.read(body, "$.verified");
                    String presFormat = JsonPath.read(body, "$.pres.formats.[0].attach_id");
                    if (presFormat.equals("indy"))
                        printProofResultV2(verified, presReq, pres);
                    else if (presFormat.equals("dif"))
                        printProofResultV2JsonLd(verified, presReq, pres);
                    else
                        log.warn("presFormat is not indy or dif");

                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + body);
                break;
            case "basicmessages":
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
            createWallet();
            log.info("Register did as issuer");
            createPublicDid();
        }
        else {
            updateEndpoint();
        }

        log.info("Create schema and credential definition");
        createSchema();
        createCredentialDefinition();

        log.info("Create key method did");
        createKeyDid();

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
        log.info("- keyDid: " + keyDid);

        log.info("Initialization is done.");
        log.info("Run alice now.");
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

    public String obtainStewardJwtToken() {
        String stewardWallet = "steward";
        String params = "?wallet_name=" + stewardWallet;

        // check if steward wallet already exists
        String response = client.requestGET(randomStr(apiUrls) + "/multitenancy/wallets" + params, null);
        log.info("response: " + response);
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

    public void createPublicDid() {
        String body = JsonPath.parse("{ seed: '" + seed + "'}").jsonString();
        log.info("Create a new local did:" + prettyJson(body));
        String response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/create", jwtToken, body);
        log.info("response:" + response);
        did = JsonPath.read(response, "$.result.did");
        verkey = JsonPath.read(response, "$.result.verkey");
        log.info("created did: " + did + ", verkey: " + verkey);

        String params = "?did=" + did +
                "&verkey=" + verkey +
                "&role=ENDORSER";
        log.info("Register the did to the ledger as a ENDORSER by steward");
        response = client.requestPOST(randomStr(apiUrls) + "/ledger/register-nym" + params, stewardJwtToken, "{}");
        log.info("response: " + response);

        params = "?did=" + did;
        log.info("Assign the did to public: " + did);
        response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/public" + params, jwtToken, "{}");
        log.info("response: " + response);
    }

    public void createKeyDid() {
        String body = JsonPath.parse("{" +
                "  method: 'key'," +
                "  options: {" + "" +
                "    key_type: 'bls12381g2'" +
                "  }" +
                "}").jsonString();
        log.info("Create a new key did:" + body);
        String response = client.requestPOST(randomStr(apiUrls) + "/wallet/did/create", jwtToken, body);
        log.info("response: " + response);
        keyDid = JsonPath.read(response, "$.result.did");
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
        log.info("response:" + response);
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
        log.info("response:" + response);
        credDefId = JsonPath.read(response, "$.credential_definition_id");
    }

    public void sendCredProblemReport(String credExId, String description) {
        String body = JsonPath.parse("{" +
                "  description: '" + description + "'" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/records/" + credExId + "/problem-report", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendCredProblemReportV2(String credExId, String description) {
        String body = JsonPath.parse("{" +
                "  description: '" + description + "'" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/records/" + credExId + "/problem-report", jwtToken, body);
        log.info("response: " + response);
    }

    public boolean checkCredentialProposal(String credExId, String credentialProposal) {
        // uncomment below if you want to get requested credential definition id from alice
        //String requestedCredDefId = JsonPath.read(credentialProposal, "$.cred_def_id");

        if (enableCredProblem) {
            sendCredProblemReport(credExId, "issue credential error message");
            return false;
        }
        return true;
    }

    public boolean checkCredentialProposalV2(String credExId, String credProposal) {
        // uncomment below if you want to get requested credential definition id from alice
        //String encodedData = JsonPath.read(credProposal, "$.filters~attach.[0].data.base64");
        //String filter = new String(Base64.decodeBase64(encodedData));
        //String requestedCredDefId = JsonPath.read(filter, "$.cred_def_id");

        if (enableCredProblem) {
            sendCredProblemReportV2(credExId, "issue credential error message");
            return false;
        }
        return true;
    }

    public boolean checkCredentialProposalV2JsonLd(String credExId, String credProposal) {
        // uncomment below if you want to get requested credential definition id from alice
        //String encodedData = JsonPath.read(credProposal, "$.filters~attach.[0].data.base64");
        //String filter = new String(Base64.decodeBase64(encodedData));

        if (enableCredProblem) {
            sendCredProblemReportV2(credExId, "issue credential error message");
            return false;
        }
        return true;
    }

    public void sendCredentialOffer(String credExId) {
        String encodedImage = "base64EncodedJpegImage";
        // uncomment below if you want to use actual encoded jpeg image
        //try {
        //    encodedImage = encodeFileToBase64Binary(photoFileName);
        //} catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  counter_proposal: {" +
                "    cred_def_id: '" + credDefId + "'," +
                "    credential_proposal: {" +
                "      attributes: [" +
                "        { name: 'name', value: 'alice' }," +
                "        { name: 'date', value: '05-2018' }," +
                "        { name: 'degree', value: 'maths' }," +
                "        { name: 'age', value: '25' }," +
                "        { name: 'photo', value: '" + encodedImage + "', mime-type: 'image/jpeg' }" +
                "      ]" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential/records/" + credExId + "/send-offer", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendCredentialOfferV2(String credExId) {
        String encodedImage = "base64EncodedJpegImage";
        // uncomment below if you want to use actual encoded jpeg image
        //try {
        //    encodedImage = encodeFileToBase64Binary(photoFileName);
        //} catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  counter_preview: {" +
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
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/records/" + credExId + "/send-offer", jwtToken, body);
        log.info("response: " + response);
    }

    // TODO: Replace with sendCredentialOfferV2 when bug fixed
    public void sendCredentialOfferV2WithNew(String connectionId) {
        String encodedImage = "base64EncodedJpegImage";
        // uncomment below if you want to use actual encoded jpeg image
        //try {
        //    encodedImage = encodeFileToBase64Binary(photoFileName);
        //} catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
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
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/send-offer", jwtToken, body);
        log.info("response: " + response);
    }

    // TODO: Replace with sendCredentialOfferV2 when bug fixed
    public void sendCredentialOfferV2WithNewJsonLd(String connectionId) {
        String encodedImage = "base64EncodedJpegImage";
        // uncomment below if you want to use actual encoded jpeg image
        //try {
        //    encodedImage = encodeFileToBase64Binary(photoFileName);
        //} catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  filter: {" +
                "    ld_proof: {" +
                "      credential: {" +
                "        @context: [" +
                "          https://www.w3.org/2018/credentials/v1," +
                "          https://w3id.org/citizenship/v1" +
                "        ]," +
                "        type: [" +
                "          VerifiableCredential," +
                "          PermanentResident" +
                "        ]," +
                "        id: 'https://credential.example.com/residents/1234567890'," +
                "        issuer: '" + keyDid + "'," +
                "        issuanceDate: '2020-01-01T12:00:00Z'," +
                "        credentialSubject: {" +
                "          type: [ PermanentResident ]," +
                // TODO "          id: 'did:key of holder'," +
                "          givenName: 'ALICE'," +
                "          familyName: 'SMITH'," +
                "          gender: 'Female'," +
                "          birthCountry: 'Bahamas'," +
                "          birthDate: '1958-07-17'," +
                "        }," +
                "      }," +
                "      options: {" +
                "        proofType: 'BbsBlsSignature2020'," +
                "      }," +
                "    }" +
                "  }," +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/issue-credential-2.0/send-offer", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendPresentProblemReport(String presExId, String description) {
        String body = JsonPath.parse("{" +
                "  description: '" + description + "'" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof/records/" + presExId + "/problem-report", jwtToken, body);
        log.info("response: " + response);
    }

    public void sendPresentProblemReportV2(String presExId, String description) {
        String body = JsonPath.parse("{" +
                "  description: '" + description + "'" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof-2.0/records/" + presExId + "/problem-report", jwtToken, body);
        log.info("response: " + response);
    }

    public String getPresFormat(String presProposal) {
        try {
            String format = JsonPath.read(presProposal, "$.formats.[0].attach_id");
            return format;
        } catch (PathNotFoundException e) {
            log.error("format is not found");
            return "";
        }
    }

    public boolean checkPresentationProposal(String presExId, String presentationProposal) {
        // currently presentationProposal does not need to parse

        if (enablePresentProblem) {
            sendPresentProblemReport(presExId, "presentation proof error message");
            return false;
        }
        return true;
    }

    public boolean checkPresentationProposalV2(String presExId, String presProposal) {
        // currently presProposal does not need to parse

        if (enablePresentProblem) {
            sendPresentProblemReportV2(presExId, "presentation proof error message");
            return false;
        }
        return true;
    }

    public boolean checkPresentationProposalV2JsonLd(String presExId, String presProposal) {
        // currently presProposal does not need to parse

        if (enablePresentProblem) {
            sendPresentProblemReportV2(presExId, "presentation proof error message");
            return false;
        }
        return true;
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
        log.info("response: " + response);
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
        log.info("response: " + response);
    }

    public void sendProofRequestV2JsonLd(String connectionId) {
        String body = JsonPath.parse("{" +
                "  comment: 'test proof request for json-ld'," +
                "  connection_id: '" + connectionId + "'," +
                "  presentation_request: {" +
                "    dif: {" +
                "      options: {" +
                "        challenge: '3fa85f64-5717-4562-b3fc-2c963f66afa7'," +
                "        domain: '4jt78h47fh47'" +
                "      }," +
                "      presentation_definition: {" +
                "        id: '32f54163-7166-48f1-93d8-ff217bdb0654'," +
                "        format: {" +
                "          ldp_vp: { proof_type: [ BbsBlsSignature2020 ] }" +
                "        }," +
                "        input_descriptors: [" +
                "          {" +
                "            id: 'citizenship_input_1'," +
                "            name: 'EU Driver License'," +
                "            schema: [" +
                "              { uri: 'https://www.w3.org/2018/credentials#VerifiableCredential' }," +
                "              { uri: 'https://w3id.org/citizenship#PermanentResident' }" +
                "            ]," +
                "            constraints: {" +
                "              limit_disclosure: 'required'," +
                "              fields: [" +
                "                {" +
                "                  path: [ $.credentialSubject.familyName ]," +
                "                  purpose: 'The claim must be from one of the specified person'," +
                "                  filter: { const: 'SMITH' }," +
                "                }," +
                "                {" +
                "                  path: [ $.credentialSubject.givenName ]," +
                "                  purpose: 'The claim must be from one of the specified person'" +
                "                }," +
                "              ]," +
                "            }," +
                "          }," +
                "        ]," +
                "      }," +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(randomStr(apiUrls) + "/present-proof-2.0/send-request", jwtToken, body);
        log.info("response: " + response);
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

    public void printProofResultV2JsonLd(String verified, String presReq, String pres) {
        if (!verified.toLowerCase(Locale.ROOT).equals("true")) {
            log.info("proof is not verified");
            return;
        }
        log.info("Proof is verified");

        LinkedHashMap<String, Object> verifCred = JsonPath.read(pres, "$.presentations~attach.[0].data.json.verifiableCredential.[0]");
        verifCred.remove("proof"); // only print essential contents
        log.info("verifiable credential: " + prettyJson(JsonPath.parse(verifCred).jsonString()));
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