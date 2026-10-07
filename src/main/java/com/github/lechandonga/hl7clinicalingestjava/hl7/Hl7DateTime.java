package com.github.lechandonga.hl7clinicalingestjava.hl7;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * HL7 v2 DTM/TS 日期时间解析，兼容 2.3 的 8 位时间精度与 2.5 的 DTM（含时区）。
 * 格式：YYYY[MM[DD[HH[mm[SS[.S+]]]]]]][+/-ZZZZ]
 */
public final class Hl7DateTime {

    private Hl7DateTime() {
    }

    public static OffsetDateTime parse(String raw, String location) {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD, location, "date/time is empty");
        }
        String text = raw.trim();

        ZoneOffset offset = ZoneOffset.UTC;
        int split = timezoneStart(text);
        String body = text;
        if (split > 0) {
            body = text.substring(0, split);
            offset = parseOffset(text.substring(split));
        }

        int dot = body.indexOf('.');
        String digitsPart = dot >= 0 ? body.substring(0, dot) : body;
        if (dot >= 0 && (dot == body.length() - 1 || !body.substring(dot + 1).chars().allMatch(Character::isDigit))) {
            throw invalid(raw, location);
        }
        if (digitsPart.chars().anyMatch(c -> !Character.isDigit(c))) {
            throw invalid(raw, location);
        }

        int len = digitsPart.length();
        if (len != 4 && len != 6 && len != 8 && len != 10 && len != 12 && len != 14) {
            throw invalid(raw, location);
        }

        int year = segment(digitsPart, 0, 4);
        int month = len >= 6 ? segment(digitsPart, 4, 6) : 1;
        int day = len >= 8 ? segment(digitsPart, 6, 8) : 1;
        int hour = len >= 10 ? segment(digitsPart, 8, 10) : 0;
        int minute = len >= 12 ? segment(digitsPart, 10, 12) : 0;
        int second = len == 14 ? segment(digitsPart, 12, 14) : 0;

        try {
            return LocalDateTime.of(year, month, day, hour, minute, second).atOffset(offset)
                    .withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw invalid(raw, location);
        }
    }

    private static int segment(String text, int start, int end) {
        return Integer.parseInt(text.substring(start, end));
    }

    private static int timezoneStart(String text) {
        for (int i = 4; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '+' || c == '-') {
                return i;
            }
        }
        return -1;
    }

    private static ZoneOffset parseOffset(String zone) {
        if (zone.length() != 5) {
            throw new NumberFormatException("timezone must be +/-HHMM");
        }
        int hours = Integer.parseInt(zone.substring(1, 3));
        int minutes = Integer.parseInt(zone.substring(3, 5));
        int total = hours * 60 + minutes;
        return ZoneOffset.ofTotalSeconds((zone.charAt(0) == '-' ? -total : total) * 60);
    }

    private static Hl7ParseException invalid(String raw, String location) {
        return new Hl7ParseException(Hl7ParseException.ErrorCode.INVALID_DATETIME, location,
                "value [" + maskForDiagnostic(raw) + "] is not a valid HL7 date/time");
    }

    private static String maskForDiagnostic(String raw) {
        return raw.length() <= 8 ? raw : raw.substring(0, 4) + "***";
    }
}
