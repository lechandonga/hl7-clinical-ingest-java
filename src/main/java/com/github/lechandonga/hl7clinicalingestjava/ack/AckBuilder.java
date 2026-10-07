package com.github.lechandonga.hl7clinicalingestjava.ack;

import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7ParseException;
import com.github.lechandonga.hl7clinicalingestjava.service.IngestOutcome;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 按入站消息版本构造 HL7 ACK：
 * <ul>
 *   <li>MSH-9：2.3 为 {@code ACK}，2.5 为 {@code ACK^A01^ACK} 风格（结构码 ACK）。</li>
 *   <li>MSA：MSA-1 AA/AE，MSA-2 回放控制号，MSA-3 人类可读错误文本。</li>
 *   <li>ERR：2.3 使用错误位置^0^代码 的紧凑形式（ERR-1 ELD 风格）；
 *       2.5 使用 ERL（ERR-1）+ HL70357 条件码（ERR-2）+ 严重级别 E（ERR-4）。</li>
 * </ul>
 */
public final class AckBuilder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private AckBuilder() {
    }

    public static String build(IngestOutcome outcome) {
        char fs = '|';
        String cs = "^~\\&";
        boolean version25 = outcome.version() != null
                && !outcome.version().startsWith("2.3");

        StringBuilder sb = new StringBuilder();
        sb.append("MSH").append(fs).append(cs).append(fs)
                .append("LOCAL-INGEST").append(fs)
                .append("LOCAL").append(fs)
                .append(safe(outcome.version())).append(fs)
                .append("LOCAL").append(fs)
                .append(now()).append(fs)
                .append(fs);
        if (version25) {
            sb.append("ACK^ACK^ACK");
        } else {
            sb.append("ACK");
        }
        sb.append(fs).append("ACK-" + correlationPart(outcome)).append(fs)
                .append("P").append(fs)
                .append(safe(outcome.version())).append(fs);
        sb.append('\r');

        sb.append("MSA").append(fs)
                .append(outcome.ackStatus().name()).append(fs)
                .append(safe(outcome.controlId())).append(fs);
        if (outcome.ackStatus() == IngestOutcome.AckStatus.AE) {
            sb.append(safe(errorText(outcome)));
        } else {
            sb.append(outcome.disposition().name());
        }
        sb.append('\r');

        if (outcome.ackStatus() == IngestOutcome.AckStatus.AE && outcome.errorCode() != null) {
            if (version25) {
                sb.append("ERR").append(fs)
                        .append(safe(outcome.errorLocation())).append("^^^0").append(fs)
                        .append(outcome.errorCode().hl7Condition())
                        .append("^").append(outcome.errorCode().code())
                        .append("^HL70357").append(fs)
                        .append(fs)
                        .append("E").append(fs)
                        .append(safe(errorText(outcome)));
            } else {
                sb.append("ERR").append(fs)
                        .append(safe(outcome.errorLocation())).append("^^0^")
                        .append(outcome.errorCode().code())
                        .append(":").append(outcome.errorCode().hl7Condition())
                        .append(fs).append(fs).append(fs)
                        .append("E").append(fs)
                        .append(safe(errorText(outcome)));
            }
            sb.append('\r');
        }
        return sb.toString();
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(TS);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.replaceAll("[|^~\\\\&]", "?");
    }

    private static String correlationPart(IngestOutcome outcome) {
        return outcome.correlationId() == null ? "0000000000" : outcome.correlationId();
    }

    private static String errorText(IngestOutcome outcome) {
        Hl7ParseException.ErrorCode code = outcome.errorCode();
        String text = code.code() + " " + code.description();
        if (outcome.errorLocation() != null) {
            text += " at " + outcome.errorLocation();
        }
        if (outcome.errorDetail() != null) {
            text += " [" + outcome.disposition() + "]";
        }
        return text;
    }
}
