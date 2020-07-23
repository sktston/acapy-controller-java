package com.sktelecom.ston.controller.faber;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.PostConstruct;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static com.sktelecom.ston.controller.utils.Common.*;

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
            response = requestGET(adminUrl, "/revocation/active-registry/" + credDefId, 30);
            revRegId = JsonPath.read(response, "$.result.revoc_reg_id");
        }

        log.info("Controller uses below configuration");
        log.info("- credential definition ID:" + credDefId);
        log.info("- revocation registry ID:" + revRegId);

        log.info("initialize <<< done");
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

        body = "{}";
        log.info("Publish the revocation registry on the ledger:");
        response = requestPOST(adminUrl,"/revocation/registry/" + revRegId + "/publish", body, 30);

        log.info("Get tails file of the revocation registry:");
        byte[] tailsFile =  WebClient.create(adminUrl).get()
                .uri("/revocation/registry/" + revRegId + "/tails-file")
                .retrieve()
                .bodyToMono(ByteArrayResource.class)
                .map(ByteArrayResource::getByteArray)
                .block(Duration.ofSeconds(30));

        log.info("Get genesis file of the revocation registry:");
        String genesis =  WebClient.create(vonNetworkUrl).get()
                .uri("/genesis")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        log.info("Put tails file to tails file server:");
        response =  WebClient.create(tailsServerUrl).put()
                .uri("/" + revRegId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("genesis", genesis).with("tails", tailsFile))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        log.info("createCredDef <<<");
    }

    public String createInvitation() {
        log.info("createInvitation >>>");
        String response = requestPOST(adminUrl,"/connections/create-invitation", "{}", 30);
        String invitation = JsonPath.parse((LinkedHashMap)JsonPath.read(response, "$.invitation")).jsonString();
        log.info("createInvitation <<< invitation:" + invitation);
        return invitation;
    }

    public void sendCredentialOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  revoc_reg_id: '" + revRegId + "'," +
                "  credential_preview: {" +
                "    @type: 'did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/credential-preview'," +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }" +
                "    ]" +
                "  }," +
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
                "      attr_1: {" +
                "        name: 'name'," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_2: {" +
                "        name: 'date'," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_3: {" +
                "        name: 'degree'," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "      pred_1: {" +
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


    public void handleMessage(String topic, String body) {
        log.info("handleMessage >>> topic:" + topic + ", body:" + body);

        String state = JsonPath.read(body, "$.state");
        switch(topic) {
            case "connections":
                // When connection with alice is done, send credential offer
                if (state.equals("active")) {
                    log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> sendCredentialOffer");
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> No action in demo");
                }
                break;
            case "issue_credential":
                // When credential is issued and acked, send proof(presentation) request
                if (state.equals("credential_acked")) {
                    log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> sendProofRequest");
                    sendProofRequest(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> No action in demo");
                }
                break;
            case "present_proof":
                // When proof is verified, print the result
                if (state.equals("verified")) {
                    log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> Print result");
                    String verified = JsonPath.read(body, "$.verified");
                    String requestedProof = JsonPath.read(body, "$.presentation.requested_proof");
                    log.info("Proof validation:" + verified);
                    log.info("Proof requested:" + prettyJson(requestedProof));
                }
                else {
                    log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> No action in demo");
                }
                break;
            case "basicmessages":
                log.info("handleMessage - Case of topic:" + topic + ", state:" + state + " -> Print message");
                String content = JsonPath.read(body, "$.content");
                log.info("message:" + content);
                break;
            default:
                log.warn("handleMessage - Unexpected topic:" + topic);
        }

        log.info("handleMessage <<<");
    }

}