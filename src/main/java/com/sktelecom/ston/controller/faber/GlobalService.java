package com.sktelecom.ston.controller.faber;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static com.sktelecom.ston.controller.utils.Common.*;
import static com.sktelecom.ston.controller.utils.Common.requestGET;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    final String adminUrl = "http://localhost:8021";
    final String tailsServerUrl = "http://13.124.169.12";
    final String vonNetworkUrl = "http://54.180.86.51";

    String version; // version for schemaId and credDefId
    String schemaId; // schema identifier
    String credDefId; // credential definition identifier
    String revRegId; // revocation registry identifier

    // check faber or faber_revoke
    static String enableRevoke = System.getenv().getOrDefault("ENABLE_REVOKE", "false");

    @PostConstruct
    public void initialize() throws Exception {
        log.info("initialize >>> start");

        String response = requestGET(adminUrl, "/credential-definitions/created", 30);
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

        log.info("initialize <<< done");
    }

    public String createInvitation() {
        log.info("createInvitation >>>");
        String response = requestPOST(adminUrl,"/connections/create-invitation", "{}", 30);
        String invitation = JsonPath.parse((LinkedHashMap)JsonPath.read(response, "$.invitation")).jsonString();
        log.info("createInvitation <<< invitation:" + invitation);
        return invitation;
    }

    public String createInvitationUrl() {
        log.info("createInvitationUrl >>>");
        String response = requestPOST(adminUrl,"/connections/create-invitation", "{}", 30);
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
                    if (enableRevoke.equals("true")) {
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
        String response = requestPOST(adminUrl,"/schemas", body, 30);
        schemaId = JsonPath.read(response, "$.schema_id");

        log.info("createSchema <<<");
    }

    public void createCredDef() {
        log.info("createCredDef >>>");

        String body;
        String response;

        body = JsonPath.parse("{" +
                "  schema_id: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  support_revocation: true" +
                "}").jsonString();
        log.info("Create a new credential definition on the ledger:" + prettyJson(body));
        response = requestPOST(adminUrl,"/credential-definitions", body, 30);
        credDefId = JsonPath.read(response, "$.credential_definition_id");

        body = JsonPath.parse("{" +
                "  max_cred_num: 100," +
                "  credential_definition_id: '" + credDefId + "'," +
                "  issuance_by_default: true" +
                "}").jsonString();
        log.info("Create a new revocation registry:" + prettyJson(body));
        response = requestPOST(adminUrl,"/revocation/create-registry", body, 30);
        revRegId = JsonPath.read(response, "$.result.revoc_reg_id");

        body = JsonPath.parse("{" +
                "  tails_public_uri: '" + tailsServerUrl + "/" + revRegId + "'" +
                "}").jsonString();
        log.info("Update tails file location of the revocation registry:" + prettyJson(body));
        response = requestPATCH(adminUrl,"/revocation/registry/" + revRegId, body, 30);

        log.info("Publish the revocation registry on the ledger:");
        response = requestPOST(adminUrl,"/revocation/registry/" + revRegId + "/publish", "{}", 30);

        log.info("Get tails file of the revocation registry:");
        ByteArrayResource tailsFile = requestGETByteArray(adminUrl, "/revocation/registry/" + revRegId + "/tails-file", 30);

        log.info("Get genesis file of the revocation registry:");
        String genesis =  requestGET(vonNetworkUrl, "/genesis", 30);

        log.info("Put tails file to tails file server:");

        MultiValueMap<String, Object> multiData = new LinkedMultiValueMap<>();
        multiData.add("genesis", genesis);
        multiData.add("tails", tailsFile);
        response = requestPUT(tailsServerUrl, "/" + revRegId, multiData,30);

        log.info("response:" + response);

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
        String response = requestPOST(adminUrl,"/issue-credential/send-offer", body, 30);
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
        String response = requestPOST(adminUrl ,"/present-proof/send-request", body, 30);
    }

    public void printProofResult(String body) {
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation.requested_proof")).jsonString();
        log.info("  - Proof requested:" + prettyJson(requestedProof));
        String verified = JsonPath.read(body, "$.verified");
        log.info("  - Proof validation:" + verified);
    }

    public void revokeCredential(String revRegId, String credRevId) {
        log.info("revokeCredential >>> revRegId:" + revRegId + ", credRevId:" + credRevId);

        String uri = UriComponentsBuilder.fromPath("/issue-credential/revoke")
                .queryParam("rev_reg_id", revRegId)
                .queryParam("cred_rev_id", credRevId)
                .queryParam("publish", true)
                .build().toUriString();
        String response =  requestPOST(adminUrl, uri, "{}", 30);
    }

}