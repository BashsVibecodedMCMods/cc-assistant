package dev.ccassistant.core;

/**
 * An expected failure with a stable error code (see docs/LUA_API.md, "Error codes"). The message is shown to Lua and
 * players, so it must never contain keys, base URLs or headers.
 */
public final class AiException extends Exception {
    public static final String NOT_CONFIGURED = "not_configured";
    public static final String NOT_LINKED = "not_linked";
    public static final String OWNER_OFFLINE = "owner_offline";
    public static final String OWNER_NO_CLIENT = "owner_no_client";
    public static final String OWNER_DISABLED = "owner_disabled";
    public static final String BAD_PROFILE = "bad_profile";
    public static final String COMMAND_COMPUTER = "command_computer";
    public static final String BAD_REQUEST = "bad_request";
    public static final String TOO_LARGE = "too_large";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String BUSY = "busy";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";
    public static final String PROVIDER_ERROR = "provider_error";
    public static final String NETWORK_ERROR = "network_error";
    public static final String BAD_RESPONSE = "bad_response";

    private final String code;

    public AiException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
