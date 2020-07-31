package com.sktelecom.ston.controller.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Random;
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
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    public static String requestGET(String url) {
        OkHttpClient client = new OkHttpClient.Builder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String requestPOST(String url, String json) {
        OkHttpClient client = new OkHttpClient.Builder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
