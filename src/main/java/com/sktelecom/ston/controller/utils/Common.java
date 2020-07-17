package com.sktelecom.ston.controller.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.JsonPath;
import com.sun.jna.Library;
import com.sun.jna.Native;
import org.apache.commons.cli.*;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Random;
import java.util.logging.*;

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
}
