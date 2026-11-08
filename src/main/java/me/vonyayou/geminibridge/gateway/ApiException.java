package me.vonyayou.geminibridge.gateway;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final String param;

    public ApiException(int status, String code, String message) { this(status, code, message, null); }
    public ApiException(int status, String code, String message, String param) {
        super(message);
        this.status = status;
        this.code = code;
        this.param = param;
    }
    public int status() { return status; }
    public String code() { return code; }
    public String param() { return param; }
}
