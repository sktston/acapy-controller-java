package com.sktelecom.ston.controller.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Slf4j
public class HttpClient {
    static final MediaType JSON_TYPE = MediaType.parse("application/json");
    static final int timeout = 3600; // 1 hour

    final OkHttpClient client = new OkHttpClient.Builder()
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(timeout, TimeUnit.SECONDS)
            .build();

    public String requestGET(String url, String token) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        request = addToken(request, token);
        return raw(request);
    }

    public String requestPOST(String url, String token, String json) {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_TYPE))
                .build();
        request = addToken(request, token);
        return raw(request);
    }

    public String requestPUT(String url, String token, String json) {
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(json, JSON_TYPE))
                .build();
        request = addToken(request, token);
        return raw(request);
    }

    public String requestDELETE(String url, String token) {
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();
        request = addToken(request, token);
        return raw(request);
    }

    public String requestPATCH(String url, String token, String json) {
        Request request = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(json, JSON_TYPE))
                .build();
        request = addToken(request, token);
        return raw(request);
    }

    String raw(Request req) {
        String result = null;
        try (Response resp = client.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                result = resp.body().string();
            } else if (!resp.isSuccessful()) {
                log.error("code={} message={}", resp.code(), resp.message());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    Request addToken(Request request, String token) {
        if (token != null && !token.equals(""))
            request = request.newBuilder().addHeader("Authorization", "Bearer " + token).build();
        return request;
    }
}
