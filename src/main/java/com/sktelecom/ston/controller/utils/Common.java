package com.sktelecom.ston.controller.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;

import java.util.concurrent.ThreadLocalRandom;

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
}
