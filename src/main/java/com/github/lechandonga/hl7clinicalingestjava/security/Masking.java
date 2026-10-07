package com.github.lechandonga.hl7clinicalingestjava.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 脱敏策略：对外响应与日志中禁止出现姓名、证件号、电话原文；
 * 保留“短哈希标识 + 部分明文后缀”，使同一条数据在排查时仍可关联。
 */
public final class Masking {

    private Masking() {
    }

    /** 姓名：整体按一个词处理，保留首末字符；空姓名不产生可关联标识。 */
    public static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        String name = fullName.trim();
        if (name.length() == 1) {
            return "*";
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(Math.min(name.length() - 2, 4)) + name.charAt(name.length() - 1);
    }

    /** 证件号/任意标识符：保留后 4 位（不足 4 位全掩码），附短哈希便于关联。 */
    public static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String suffix = trimmed.length() <= 4 ? "*".repeat(trimmed.length())
                : "*".repeat(Math.min(trimmed.length() - 4, 8)) + trimmed.substring(trimmed.length() - 4);
        return suffix + "#" + shortHash(trimmed);
    }

    /** 电话：仅保留后 2 位。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return "****";
        }
        String kept = digits.length() <= 2 ? "*".repeat(digits.length())
                : "*".repeat(digits.length() - 2) + digits.substring(digits.length() - 2);
        return kept + "#" + shortHash(digits);
    }

    /** 出生日期：仅保留年份，月日以 ** 替代。 */
    public static String maskDateOfBirth(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() < 4) {
            return null;
        }
        return yyyymmdd.substring(0, 4) + "**" + "**";
    }

    public static String shortHash(String value) {
        return sha256(value).substring(0, 10);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
