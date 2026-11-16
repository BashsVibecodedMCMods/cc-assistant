package dev.ccassistant.provider;

import com.google.gson.JsonObject;

import java.util.Map;

/** One HTTP request a protocol wants made. The body is null for GET. */
public record HttpSpec(String url, Map<String, String> headers, JsonObject body) {
}
