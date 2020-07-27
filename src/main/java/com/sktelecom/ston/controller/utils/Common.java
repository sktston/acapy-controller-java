package com.sktelecom.ston.controller.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Random;

@RequiredArgsConstructor
@Slf4j
public class Common {
    public static String prettyJson(String jsonString) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(JsonParser.parseString(jsonString));
    }

    public static int getRandomInt(int min, int max) {
        if (min >= max)
            throw new IllegalArgumentException("max must be greater than min");
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    public static String requestGET(String url, String uri, int timeoutSec) {
        return WebClient.create(url).get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(timeoutSec));
    }

    public static ByteArrayResource requestGETByteArray(String url, String uri, int timeoutSec) {
        return WebClient.create(url).get()
                .uri(uri)
                .retrieve()
                .bodyToMono(ByteArrayResource.class)
                .block(Duration.ofSeconds(timeoutSec));
    }

    public static String requestPOST(String url, String uri, String body, int timeoutSec) {
        return WebClient.create(url).post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromObject(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(timeoutSec));
    }

    public static String requestPATCH(String url, String uri, String body, int timeoutSec) {
        return WebClient.create(url).patch()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromObject(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(timeoutSec));
    }

    public static String requestPUT(String url, String uri, MultiValueMap<String, Object> body, int timeoutSec) {
        return WebClient.create(url).put()
                .uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

}
