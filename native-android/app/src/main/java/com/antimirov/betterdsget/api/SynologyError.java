package com.antimirov.betterdsget.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Synology API error — returned by the NAS in the JSON response body.
 * Strictly separated from {@link NetworkError} so transient network glitches
 * never trigger a session logout.
 */
public class SynologyError extends Exception {
    private final int code;
    private final String apiName;
    private final Object details;

    private static final Map<Integer, String> CODE_MESSAGES = new HashMap<>();
    private static final Map<Integer, String> DS_CODE_MESSAGES = new HashMap<>();

    static {
        CODE_MESSAGES.put(100, "Unknown error");
        CODE_MESSAGES.put(101, "Bad request");
        CODE_MESSAGES.put(102, "No such API");
        CODE_MESSAGES.put(103, "No such method");
        CODE_MESSAGES.put(104, "API version not supported");
        CODE_MESSAGES.put(105, "No permission");
        CODE_MESSAGES.put(106, "Session timeout");
        CODE_MESSAGES.put(107, "Session interrupted (duplicate login)");
        CODE_MESSAGES.put(117, "Busy");
        CODE_MESSAGES.put(119, "Missing SID");
        CODE_MESSAGES.put(150, "IP blocked due to too many failed attempts");
        CODE_MESSAGES.put(160, "Insufficient application privilege");
        CODE_MESSAGES.put(400, "Account not found");
        CODE_MESSAGES.put(401, "Account disabled");
        CODE_MESSAGES.put(402, "Permission denied");
        CODE_MESSAGES.put(403, "OTP code required");
        CODE_MESSAGES.put(404, "OTP authentication failed");
        CODE_MESSAGES.put(406, "OTP enforcement required");
        CODE_MESSAGES.put(407, "Blocked IP — too many login failures");
        CODE_MESSAGES.put(408, "Expired password, cannot change");
        CODE_MESSAGES.put(409, "Expired password");
        CODE_MESSAGES.put(410, "Password must be changed");

        DS_CODE_MESSAGES.put(400, "File upload failed");
        DS_CODE_MESSAGES.put(401, "Max number of tasks reached");
        DS_CODE_MESSAGES.put(402, "Destination denied");
        DS_CODE_MESSAGES.put(403, "Destination does not exist");
        DS_CODE_MESSAGES.put(404, "Invalid task ID");
        DS_CODE_MESSAGES.put(405, "Invalid task action");
        DS_CODE_MESSAGES.put(406, "No default destination");
        DS_CODE_MESSAGES.put(407, "Set destination failed");
        DS_CODE_MESSAGES.put(408, "File does not exist");
    }

    public SynologyError(String message, int code) {
        this(message, code, "", null);
    }

    public SynologyError(String message, int code, String apiName, Object details) {
        super(message);
        this.code = code;
        this.apiName = apiName != null ? apiName : "";
        this.details = details;
    }

    public int getCode() {
        return code;
    }

    public String getApiName() {
        return apiName;
    }

    public Object getDetails() {
        return details;
    }

    public boolean isSessionError() {
        return code == 106 || code == 107 || code == 119;
    }

    public boolean isTransient() {
        return code == 100 || code == 117;
    }

    public boolean isAuthError() {
        if (apiName.contains("DownloadStation")) {
            return code == 105;
        }
        return code == 105 || (code >= 400 && code <= 408);
    }

    public boolean isOtpRequired() {
        if (!apiName.contains("DownloadStation") && (code == 403 || code == 404 || code == 406)) {
            return true;
        }
        String msg = getMessage() != null ? getMessage().toLowerCase() : "";
        return msg.contains("otp") || msg.contains("2-step") || msg.contains("two-factor");
    }

    public static SynologyError fromCode(int code, String apiName, Object details) {
        String safeApi = apiName != null ? apiName : "";
        String message = CODE_MESSAGES.getOrDefault(code, "API error " + code);
        if (safeApi.contains("DownloadStation") && DS_CODE_MESSAGES.containsKey(code)) {
            message = DS_CODE_MESSAGES.get(code);
        }
        return new SynologyError(message, code, safeApi, details);
    }
}

