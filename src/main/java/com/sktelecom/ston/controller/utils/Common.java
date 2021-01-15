package com.sktelecom.ston.controller.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.codec.binary.Base64;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
        return ThreadLocalRandom.current().nextInt(min, max);
    }

    public static String randomStr(String[] strings) {
        return strings[getRandomInt(0, strings.length)];
    }


    public static String parseInvitationUrl(String invitationUrl) {
        String[] tokens = invitationUrl.split("\\?c_i=");
        if (tokens.length != 2)
            return null;

        String encodedInvitation = tokens[1];
        return new String(Base64.decodeBase64(encodedInvitation));
    }

    static OkHttpClient getClient() {
        int timeout = 3600; // 1 hour
        return new OkHttpClient.Builder()
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout, TimeUnit.SECONDS)
                .build();
    }

    static Request addToken(Request request, String token) {
        if (token != null && !token.equals(""))
            request = request.newBuilder().addHeader("Authorization", "Bearer " + token).build();
        return request;
    }

    public static String requestGET(String url, String token) {
        OkHttpClient client = getClient();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        request = addToken(request, token);
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String requestPOST(String url, String token, String json) {
        OkHttpClient client = getClient();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        request = addToken(request, token);
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String requestPUT(String url, String token, String json) {
        OkHttpClient client = getClient();
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        request = addToken(request, token);
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String requestDELETE(String url, String token) {
        OkHttpClient client = getClient();
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();
        request = addToken(request, token);
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String requestPATCH(String url, String token, String json) {
        OkHttpClient client = getClient();
        Request request = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        request = addToken(request, token);
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] requestGETtoBytes(String url, String token) {
        OkHttpClient client = getClient();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        request = addToken(request, token);
        try {
            Response response = client.newCall(request).execute();
            return response.body().bytes();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

}
