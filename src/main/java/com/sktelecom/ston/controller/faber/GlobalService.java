package com.sktelecom.ston.controller.faber;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
    final String webhookUrl = "http://host.docker.internal:8022/webhooks"; // url to receive webhook messages
    final String version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99); // for randomness
    final String walletName = "faber." + version; // new walletName
    final String seed = UUID.randomUUID().toString().replaceAll("-", ""); // random seed 32 characters
    String did; // did
    String verkey; // verification key
    String schemaId; // schema identifier
    String credDefId; // credential definition identifier

    // check options
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("Create wallet and did, register did as issuer, and register webhook url");
        createWalletAndDid();
        registerDidAsIssuer();
        registerWebhookUrl();

        log.info("Create schema and credential definition");
        createSchema();
        createCredentialDefinition();

        log.info("Configuration of faber:");
        log.info("- wallet name: " + walletName);
        log.info("- seed: " + seed);
        log.info("- did: " + did);
        log.info("- verification key: " + verkey);
        log.info("- webhook url: " + webhookUrl);
        log.info("- schema ID: " + schemaId);
        log.info("- credential definition ID: " + credDefId);

        log.info("Initialization is done.");
        log.info("Run alice now.");
    }

    public String createInvitation() {
        log.info("createInvitation >>>");
        String response = requestPOST(agentApiUrl + "/connections/create-invitation", walletName, "{}");
        String invitation = JsonPath.parse((LinkedHashMap)JsonPath.read(response, "$.invitation")).jsonString();
        log.info("createInvitation <<< invitation:" + invitation);
        return invitation;
    }

    public String createInvitationUrl() {
        log.info("createInvitationUrl >>>");
        String response = requestPOST(agentApiUrl + "/connections/create-invitation", walletName, "{}");
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
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOffer");
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"));
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
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print message");
                log.info("  - message:" + JsonPath.read(body, "$.content"));
                break;
            case "revocation_registry":
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

    public void createWalletAndDid() {
        log.info("createWalletAndDid >>>");

        String body = JsonPath.parse("{" +
                "  name: '" + walletName + "'," +
                "  key: '" + walletName + ".key'," +
                "  type: 'indy'" +
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

    public void registerDidAsIssuer() {
        log.info("registerDidAsIssuer >>>");

        String params = "?did=" + did +
                "&verkey=" + verkey +
                "&alias=" + walletName +
                "&role=ENDORSER";
        log.info("Register the did to the ledger as a ENDORSER");
        // did of admin wallet must have STEWARD role
        String response = requestPOST(agentApiUrl + "/ledger/register-nym" + params, adminWalletName, "{}");

        params = "?did=" + did;
        log.info("Assign the did to public: " + did);
        response = requestPOST(agentApiUrl + "/wallet/did/public" + params, walletName, "{}");
        log.info("response: " + response);

        log.info("registerDidAsIssuer <<<");
    }

    public void registerWebhookUrl() {
        log.info("registerWebhookUrl >>>");

        String body = JsonPath.parse("{ target_url: '" + webhookUrl + "' }").jsonString();
        log.info("Create a new webhook target:" + prettyJson(body));
        String response = requestPOST(agentApiUrl + "/webhooks", walletName, body);

        log.info("registerWebhookUrl <<<");
    }

    public void createSchema() {
        log.info("createSchema >>>");

        String body = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'date', 'degree', 'age']" +
                "}").jsonString();
        log.info("Create a new schema on the ledger:" + prettyJson(body));
        String response = requestPOST(agentApiUrl + "/schemas", walletName, body);
        schemaId = JsonPath.read(response, "$.schema_id");

        log.info("createSchema <<<");
    }

    public void createCredentialDefinition() {
        log.info("createCredentialDefinition >>>");

        String body = JsonPath.parse("{" +
                "  schema_id: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  support_revocation: true," +
                "  revocation_registry_size: 10" +
                "}").jsonString();
        log.info("Create a new credential definition on the ledger:" + prettyJson(body));
        String response = requestPOST(agentApiUrl + "/credential-definitions", walletName, body);
        credDefId = JsonPath.read(response, "$.credential_definition_id");

        log.info("createCredentialDefinition <<<");
    }

    public void sendCredentialOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  credential_preview: {" +
                "    @type: 'did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/credential-preview'," +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }" +
                "    ]" +
                "  }" +
                "}").jsonString();
        String response = requestPOST(agentApiUrl + "/issue-credential/send-offer", walletName, body);
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
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_date: {" +
                "        name: 'date'," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_degree: {" +
                "        name: 'degree'," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "      pred_age: {" +
                "        name: 'age'," +
                "        p_type: '>='," +
                "        p_value: 20," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    non_revoked: { to: " + curUnixTime + " }" +
                "  }" +
                "}").jsonString();
        String response = requestPOST(agentApiUrl + "/present-proof/send-request", walletName, body);
    }

    public void printProofResult(String body) {
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation.requested_proof")).jsonString();
        log.info("  - Proof requested:" + prettyJson(requestedProof));
        String verified = JsonPath.read(body, "$.verified");
        log.info("  - Proof validation:" + verified);
    }

    public void revokeCredential(String revRegId, String credRevId) {
        log.info("revokeCredential >>> revRegId:" + revRegId + ", credRevId:" + credRevId);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(agentApiUrl + "/issue-credential/revoke").newBuilder();
        urlBuilder.addQueryParameter("rev_reg_id", revRegId);
        urlBuilder.addQueryParameter("cred_rev_id", credRevId);
        urlBuilder.addQueryParameter("publish", "true");
        String url = urlBuilder.build().toString();

        String response =  requestPOST(url, walletName, "{}");
    }

}