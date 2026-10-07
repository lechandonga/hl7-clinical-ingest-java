package com.github.lechandonga.hl7clinicalingestjava.hl7;

/**
 * 可区分错误类型与出错位置的解析/校验异常。错误原因见 {@link ErrorCode}，
 * location 形如 {@code MSH-12}、{@code PID-3[1].4}、{@code segment#5}。
 */
public class Hl7ParseException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String location;

    public Hl7ParseException(ErrorCode errorCode, String location, String detail) {
        super(buildMessage(errorCode, location, detail));
        this.errorCode = errorCode;
        this.location = location;
    }

    public Hl7ParseException(ErrorCode errorCode, String location, String detail, Throwable cause) {
        super(buildMessage(errorCode, location, detail), cause);
        this.errorCode = errorCode;
        this.location = location;
    }

    private static String buildMessage(ErrorCode errorCode, String location, String detail) {
        String base = errorCode.code() + " " + errorCode.description();
        if (location != null && !location.isBlank()) {
            base += " at " + location;
        }
        if (detail != null && !detail.isBlank()) {
            base += ": " + detail;
        }
        return base;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String location() {
        return location;
    }

    /** 错误类型枚举：稳定的机器可读代码 + HL7 Table 0357 映射 + 人类可读说明。 */
    public enum ErrorCode {
        EMPTY_MESSAGE("HL7-1001", "100", "message is empty or blank"),
        MSH_NOT_FIRST("HL7-1002", "102", "first segment must be MSH"),
        MSH_TOO_SHORT("HL7-1003", "102", "MSH segment is too short to declare delimiters"),
        ILLEGAL_ENCODING("HL7-1004", "207", "message contains illegal or undecodable characters"),
        INVALID_ESCAPE("HL7-1005", "207", "invalid HL7 escape sequence"),
        EMPTY_SEGMENT("HL7-1006", "102", "empty segment name or segment body"),
        UNSUPPORTED_VERSION("HL7-1007", "203", "unsupported HL7 version"),
        MISSING_FIELD("HL7-1008", "101", "required field is missing"),
        INVALID_DATETIME("HL7-1009", "207", "invalid HL7 date/time value"),
        UNSUPPORTED_MESSAGE_TYPE("HL7-1010", "200", "unsupported message type or trigger event"),
        INTERNAL_ERROR("HL7-1999", "207", "unexpected internal error");

        private final String code;
        private final String hl7Condition;
        private final String description;

        ErrorCode(String code, String hl7Condition, String description) {
            this.code = code;
            this.hl7Condition = hl7Condition;
            this.description = description;
        }

        public String code() {
            return code;
        }

        /** HL7 v2 Table 0357 Message Error Condition Codes，用于 2.5 的 ERR-2.2。 */
        public String hl7Condition() {
            return hl7Condition;
        }

        public String description() {
            return description;
        }
    }
}
