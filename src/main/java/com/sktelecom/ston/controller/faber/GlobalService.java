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
import java.util.Arrays;

import static com.sktelecom.ston.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    final String adminUrl = "http://localhost:8021";
    final String tailsServerUrl = "http://13.124.169.12";
    final String vonNetworkUrl = "http://54.180.86.51";

    String invitation;
    String schemaId; // schema identifier
    String credDefId; // credential definition identifier
    String revRegId; // revocation registry identifier
    String version; // version for schemaId and credDefId

    @PostConstruct
    public void initialize() throws Exception {
        log.info("initialize >>> start");

        version = getRandomInt(1, 99) + "." + getRandomInt(1, 99) + "." + getRandomInt(1, 99);

        createSchema();

        createCredDef();

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


    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("initializeAfterStartup >>> start");

        log.info("initializeAfterStartup <<< done");
    }

}