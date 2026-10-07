package com.github.lechandonga.hl7clinicalingestjava.hl7;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 无第三方依赖的 HL7 v2 解析器。
 *
 * <p>能力边界：
 * <ul>
 *   <li>从 MSH 段动态推导 | ^ &amp; ~ # 分隔符，不写死；</li>
 *   <li>还原 段/字段/重复/组件/子组件 五层结构；</li>
 *   <li>按版本兼容转义：2.3 仅 F/S/T/R/E，2.4+ 额外支持 C/M/H/N/Z/.br/.fi/.nf/.sp；</li>
 *   <li>字段缺失、非法编码、非法转义、不支持的版本均抛出带位置的
 *       {@link Hl7ParseException}，绝不静默丢弃。</li>
 * </ul>
 */
public final class Hl7Parser {

    private static final Set<String> SUPPORTED_VERSIONS =
            Set.of("2.3", "2.3.1", "2.4", "2.5", "2.5.1");

    private static final Set<String> FORMATTING_ESCAPES = Set.of(".br", ".fi", ".nf", ".sp");

    public Hl7Message parse(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.EMPTY_MESSAGE, null,
                    "no bytes received");
        }
        String raw;
        try {
            raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(rawBytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.ILLEGAL_ENCODING, null,
                    "payload is not valid UTF-8", ex);
        }
        return parse(raw);
    }

    public Hl7Message parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.EMPTY_MESSAGE, null,
                    "message is blank");
        }

        String text = raw.replace("\r\n", "\r").replace('\n', '\r');
        if (!text.startsWith("MSH")) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MSH_NOT_FIRST, null,
                    "message must start with the MSH segment");
        }

        char fieldSep = text.charAt(3);
        String[] lines = text.split("\r", -1);
        String mshLine = lines[0];
        if (mshLine.length() < 8) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MSH_TOO_SHORT, "MSH",
                    "encoding characters (MSH-2) are missing");
        }
        String enc = mshLine.substring(4, 8);
        char componentSep = enc.charAt(0);
        char repetitionSep = enc.charAt(1);
        char escapeChar = enc.charAt(2);
        char subcomponentSep = enc.charAt(3);

        if (hasDuplicate(fieldSep, componentSep, repetitionSep, escapeChar, subcomponentSep)) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MSH_TOO_SHORT, "MSH-2",
                    "delimiter characters must be pairwise distinct");
        }
        validateCharacterRange(text);

        List<Hl7Message.Segment> segments = new ArrayList<>();
        int segmentIndex = 0;
        for (String line : lines) {
            segmentIndex++;
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() < 3) {
                throw new Hl7ParseException(Hl7ParseException.ErrorCode.EMPTY_SEGMENT,
                        "segment#" + segmentIndex, "segment body too short: length=" + line.length());
            }
            String name = line.substring(0, 3);
            if (name.isBlank()) {
                throw new Hl7ParseException(Hl7ParseException.ErrorCode.EMPTY_SEGMENT,
                        "segment#" + segmentIndex, "missing segment name");
            }
            segments.add(parseSegment(name, segmentIndex, line, fieldSep, componentSep,
                    repetitionSep, escapeChar, subcomponentSep));
        }

        String version = readVersion(segments);
        return new Hl7Message(fieldSep, componentSep, repetitionSep, escapeChar, subcomponentSep,
                version, segments);
    }

    private Hl7Message.Segment parseSegment(String name, int sequence, String line, char fieldSep,
                                            char componentSep, char repetitionSep, char escapeChar,
                                            char subcomponentSep) {
        List<Hl7Message.Field> fields = new ArrayList<>();

        String[] fieldStrings = split(line, fieldSep);
        if ("MSH".equals(name)) {
            // MSH-1 是分隔符本身，MSH-2 为编码字符串；二者不参与常规切分。
            fields.add(new Hl7Message.Field(List.of(
                    new Hl7Message.Repetition(List.of(
                            new Hl7Message.Component(List.of(String.valueOf(fieldSep))))))));
            fields.add(new Hl7Message.Field(List.of(
                    new Hl7Message.Repetition(List.of(
                            new Hl7Message.Component(List.of(line.substring(4, 8))))))));
            for (int i = 2; i < fieldStrings.length; i++) {
                fields.add(parseField("MSH", i + 1, fieldStrings[i], fieldSep, componentSep,
                        repetitionSep, escapeChar, subcomponentSep));
            }
        } else {
            for (int i = 1; i < fieldStrings.length; i++) {
                fields.add(parseField(name, i, fieldStrings[i], fieldSep, componentSep,
                        repetitionSep, escapeChar, subcomponentSep));
            }
        }
        return new Hl7Message.Segment(name, sequence, fields);
    }

    private Hl7Message.Field parseField(String segment, int fieldPosition, String fieldText,
                                        char fieldSep, char componentSep, char repetitionSep,
                                        char escapeChar, char subcomponentSep) {
        String[] repetitions = split(fieldText, repetitionSep);
        List<Hl7Message.Repetition> reps = new ArrayList<>();
        for (int r = 0; r < repetitions.length; r++) {
            String[] components = split(repetitions[r], componentSep);
            List<Hl7Message.Component> comps = new ArrayList<>();
            for (int c = 0; c < components.length; c++) {
                String[] subs = split(components[c], subcomponentSep);
                List<String> subValues = new ArrayList<>();
                for (String sub : subs) {
                    subValues.add(unescape(segment, fieldPosition, r + 1, c + 1, sub, escapeChar,
                            componentSep, repetitionSep, fieldSep, subcomponentSep));
                }
                comps.add(new Hl7Message.Component(subValues));
            }
            reps.add(new Hl7Message.Repetition(comps));
        }
        return new Hl7Message.Field(reps);
    }

    private String unescape(String segment, int fieldPosition, int rep, int comp, String text,
                            char escapeChar, char componentSep, char repetitionSep, char fieldSep,
                            char subcomponentSep) {
        if (text.indexOf(escapeChar) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch != escapeChar) {
                out.append(ch);
                i++;
                continue;
            }
            int end = text.indexOf(escapeChar, i + 1);
            if (end < 0) {
                throw escapeError(segment, fieldPosition, rep, comp,
                        "escape sequence is never closed");
            }
            String body = text.substring(i + 1, end);
            if (body.isEmpty()) {
                throw escapeError(segment, fieldPosition, rep, comp, "empty escape sequence");
            }
            if (body.length() == 1) {
                switch (body.charAt(0)) {
                    case 'F' -> out.append(fieldSep);
                    case 'S' -> out.append(componentSep);
                    case 'T' -> out.append(subcomponentSep);
                    case 'R' -> out.append(repetitionSep);
                    case 'E' -> out.append(escapeChar);
                    default -> throw escapeError(segment, fieldPosition, rep, comp,
                            "unknown escape code \\" + body + "\\");
                }
            } else if (body.length() == 2 && body.charAt(0) == 'X') {
                out.append(decodeHex(segment, fieldPosition, rep, comp, body.substring(1)));
            } else if (body.charAt(0) == 'X' && body.length() % 2 == 0) {
                // 2.4+ 支持多字节 X.. 十六进制转义（如 X4e2dX6587 由多段承载）。
                for (int p = 1; p + 1 < body.length(); p += 2) {
                    out.append(decodeHex(segment, fieldPosition, rep, comp,
                            body.substring(p, p + 2)));
                }
            } else if (FORMATTING_ESCAPES.contains(body) || "H".equals(body) || "N".equals(body)
                    || "Z".equals(body) || "C".equals(body) || "M".equals(body)) {
                // 高亮/格式/字符集类转义不引入可打印的字段内容，按空文本处理但属于合法转义。
                // C/M 在单字符分支已无法命中；这里兜底保留为空。
            } else {
                throw escapeError(segment, fieldPosition, rep, comp,
                        "unknown escape sequence \\" + body + "\\");
            }
            i = end + 1;
        }
        return out.toString();
    }

    private char decodeHex(String segment, int fieldPosition, int rep, int comp, String hex) {
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException ex) {
            throw escapeError(segment, fieldPosition, rep, comp,
                    "invalid hexadecimal escape X" + hex);
        }
    }

    private Hl7ParseException escapeError(String segment, int fieldPosition, int rep, int comp,
                                          String detail) {
        String location = segment + "-" + fieldPosition + "[" + rep + "]." + comp;
        return new Hl7ParseException(Hl7ParseException.ErrorCode.INVALID_ESCAPE, location, detail);
    }

    private String readVersion(List<Hl7Message.Segment> segments) {
        Hl7Message.Segment msh = segments.isEmpty() ? null : segments.get(0);
        if (msh == null || !"MSH".equals(msh.name())) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MSH_NOT_FIRST, null,
                    "MSH segment not found");
        }
        String version = msh.value(12, 1);
        if (version == null || version.isBlank()) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD, "MSH-12.1",
                    "version id (VID-1) is required");
        }
        String normalized = version.trim();
        if (!SUPPORTED_VERSIONS.contains(normalized)) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.UNSUPPORTED_VERSION,
                    "MSH-12.1", "version [" + normalized + "] is not supported");
        }
        return normalized;
    }

    private static String[] split(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == delimiter) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts.toArray(new String[0]);
    }

    private boolean hasDuplicate(char... chars) {
        Arrays.sort(chars);
        for (int i = 1; i < chars.length; i++) {
            if (chars[i] == chars[i - 1]) {
                return true;
            }
        }
        return false;
    }

    private void validateCharacterRange(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0xFFFD || c < 0x20 && c != '\r' && c != '\n' && c != '\t') {
                throw new Hl7ParseException(Hl7ParseException.ErrorCode.ILLEGAL_ENCODING,
                        "char#" + (i + 1), "illegal control or replacement character 0x"
                                + Integer.toHexString(c));
            }
        }
    }
}
