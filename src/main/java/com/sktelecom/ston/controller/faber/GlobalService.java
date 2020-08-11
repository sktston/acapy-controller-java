package com.sktelecom.ston.controller.faber;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static com.sktelecom.ston.controller.utils.Common.*;
import static com.sktelecom.ston.controller.utils.Common.requestGET;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    final String adminUrl = "http://localhost:8021";

    String version; // version for schemaId and credDefId
    String schemaId; // schema identifier
    String credDefId; // credential definition identifier

    // check options
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));
    static boolean enableObserveMode = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_OBSERVE_MODE", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("initializeAfterStartup >>> start");

        if(enableObserveMode)
            return;

        String response = requestGET(adminUrl + "/credential-definitions/created");
        ArrayList<String> credDefIds = JsonPath.read(response, "$.credential_definition_ids");

        if (credDefIds.size() == 0) {
            log.info("Agent does not have credential definition -> Create it");
            version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99);
            createSchema();
            createCredDef();
        }
        else {
            log.info("Agent has credential definitions -> Use first one");
            credDefId = credDefIds.get(0);
        }

        log.info("Controller uses below configuration");
        log.info("- credential definition ID:" + credDefId);

        log.info("Setting of schema and credential definition is done. Run alice now.");
        log.info("initializeAfterStartup <<< done");
    }

    public String createInvitation() {
        log.info("createInvitation >>>");
        String response = requestPOST(adminUrl + "/connections/create-invitation", "{}");
        String invitation = JsonPath.parse((LinkedHashMap)JsonPath.read(response, "$.invitation")).jsonString();
        log.info("createInvitation <<< invitation:" + invitation);
        return invitation;
    }

    public String createInvitationUrl() {
        log.info("createInvitationUrl >>>");
        String response = requestPOST(adminUrl + "/connections/create-invitation", "{}");
        String invitationUrl = JsonPath.read(response, "$.invitation_url");
        log.info("createInvitationUrl <<< invitationUrl:" + invitationUrl);
        return invitationUrl;
    }

    public void handleMessage(String topic, String body) {
        log.info("handleMessage >>> topic:" + topic + ", body:" + body);

        String state = JsonPath.read(body, "$.state");
        switch(topic) {
            case "connections":
                // When connection with alice is done, send credential offer
                if (state.equals("active") && !enableObserveMode) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOffer");
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "issue_credential":
                // When credential is issued and acked, send proof(presentation) request
                if (state.equals("credential_acked") && !enableObserveMode) {
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
                if (state.equals("verified") && !enableObserveMode) {
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
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void createSchema() {
        log.info("createSchema >>>");

        String body = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'date', 'degree', 'age']" +
                "}").jsonString();
        log.info("Create a new schema on the ledger:" + prettyJson(body));
        String response = requestPOST(adminUrl + "/schemas", body);
        schemaId = JsonPath.read(response, "$.schema_id");

        log.info("createSchema <<<");
    }

    public void createCredDef() {
        log.info("createCredDef >>>");

        String body = JsonPath.parse("{" +
                "  schema_id: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  support_revocation: true," +
                "  revocation_registry_size: 50" +
                "}").jsonString();
        log.info("Create a new credential definition on the ledger:" + prettyJson(body));
        String response = requestPOST(adminUrl + "/credential-definitions", body);
        credDefId = JsonPath.read(response, "$.credential_definition_id");

        log.info("createCredDef <<<");
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
        String response = requestPOST(adminUrl + "/issue-credential/send-offer", body);
    }

    public void sendProofRequest(String connectionId) {
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
                "    }" +
                "  }" +
                "}").jsonString();
        String response = requestPOST(adminUrl + "/present-proof/send-request", body);
    }

    public void printProofResult(String body) {
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation.requested_proof")).jsonString();
        log.info("  - Proof requested:" + prettyJson(requestedProof));
        String verified = JsonPath.read(body, "$.verified");
        log.info("  - Proof validation:" + verified);
    }

    public void revokeCredential(String revRegId, String credRevId) {
        log.info("revokeCredential >>> revRegId:" + revRegId + ", credRevId:" + credRevId);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(adminUrl + "/issue-credential/revoke").newBuilder();
        urlBuilder.addQueryParameter("rev_reg_id", revRegId);
        urlBuilder.addQueryParameter("cred_rev_id", credRevId);
        urlBuilder.addQueryParameter("publish", "true");
        String url = urlBuilder.build().toString();

        String response =  requestPOST(url, "{}");
    }

}