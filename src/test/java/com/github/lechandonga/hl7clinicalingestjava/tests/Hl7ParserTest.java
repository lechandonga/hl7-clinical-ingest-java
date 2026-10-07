package com.github.lechandonga.hl7clinicalingestjava.tests;

import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7Message;
import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7ParseException;
import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析边界用例：分隔符/转义/重复/组件层级、非法编码、字段缺失、版本支持范围。
 */
class Hl7ParserTest {

    private final Hl7Parser parser = new Hl7Parser();

    private static String msh23(String trigger) {
        return "MSH|^~\\&|SENDING|FAC1|RECV|LOCAL|20240101120000||ADT^" + trigger
                + "|CTL-1|P|2.3\r";
    }

    @Nested
    @DisplayName("业务: 层级结构还原")
    class Structure {

        @Test
        @DisplayName("用例: 重复PID-3与组件/子组件拆分 -> 结果: 正确还原五层")
        void repetitionsComponentsAndSubcomponents() {
            String raw = msh23("A04")
                    + "EVN|A04|20240101120000\r"
                    + "PID|1||MR-1234^^^X^MR~SS-9999^^^X^SS||张^三||19800101|M\r"
                    + "PV1|1|O\r";
            Hl7Message message = parser.parse(raw);
            Hl7Message.Segment pid = message.segment("PID");
            assertEquals(2, pid.field(3).repetitionCount());
            assertEquals("MR-1234", pid.value(3, 1, 1));
            assertEquals("SS-9999", pid.value(3, 2, 1));
            assertEquals("MR", pid.value(3, 1, 5));
            assertEquals("SS", pid.value(3, 2, 5));
            assertEquals("张", pid.value(5, 1, 1));
            assertEquals("三", pid.value(5, 1, 2));
        }

        @Test
        @DisplayName("用例: 2.5标识符类型位于CX-5.3 -> 结果: 组件可定位到子组件")
        void version25IdTypeSubcomponent() {
            String raw = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04^ADT_A01|C2|P|2.5\r"
                    + "PID|1||P-1^^^^NIH&MR~X||Doe^John||19800101|M\r"
                    + "PV1|1|O\r";
            Hl7Message message = parser.parse(raw);
            assertEquals("2.5", message.version());
            assertEquals("NIH", message.segment("PID").value(3, 1, 5, 1));
            assertEquals("MR", message.segment("PID").value(3, 1, 5, 2));
        }
    }

    @Nested
    @DisplayName("业务: 转义处理")
    class Escaping {

        @Test
        @DisplayName("用例: F/S/T/R/E转义与反斜杠 -> 结果: 还原为原始分隔符")
        void standardEscapes() {
            String raw = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04^ADT_A01|C3|P|2.5\r"
                    + "PID|1||P1||a\\F\\b\\S\\c\\T\\d\\R\\e\\E\\f||| \r"
                    + "PV1|1|O\r";
            Hl7Message message = parser.parse(raw);
            assertEquals("a|b^c&d~e\\f", message.segment("PID").value(5, 1, 1));
        }

        @Test
        @DisplayName("用例: 未闭合转义 -> 结果: INVALID_ESCAPE 且错误位置可定位到 PID-5")
        void invalidEscape() {
            String raw = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04^ADT_A01|C4|P|2.5\r"
                    + "PID|1||P1||bad\\Q\\name||19800101|M\r"
                    + "PV1|1|O\r";
            Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parse(raw));
            assertEquals(Hl7ParseException.ErrorCode.INVALID_ESCAPE, ex.errorCode());
            assertTrue(ex.location().startsWith("PID-5"), "位置应为 PID-5，实际: " + ex.location());
        }
    }

    @Nested
    @DisplayName("业务: 错误可区分")
    class Errors {

        @Test
        @DisplayName("用例: 空报文 -> 结果: EMPTY_MESSAGE")
        void empty() {
            Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                    () -> parser.parse("   "));
            assertEquals(Hl7ParseException.ErrorCode.EMPTY_MESSAGE, ex.errorCode());
        }

        @Test
        @DisplayName("用例: 首段非MSH -> 结果: MSH_NOT_FIRST")
        void notMshFirst() {
            Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                    () -> parser.parse("PID|1\r"));
            assertEquals(Hl7ParseException.ErrorCode.MSH_NOT_FIRST, ex.errorCode());
        }

        @Test
        @DisplayName("用例: 非UTF-8字节 -> 结果: ILLEGAL_ENCODING")
        void illegalBytes() {
            byte[] raw = new byte[]{
                    'M', 'S', 'H', '|', (byte) 0xFF, (byte) 0xFE, '\r'
            };
            Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                    () -> parser.parse(raw));
            assertEquals(Hl7ParseException.ErrorCode.ILLEGAL_ENCODING, ex.errorCode());
        }

        @Test
        @DisplayName("用例: MSH-12版本为2.2 -> 结果: UNSUPPORTED_VERSION 位置 MSH-12.1")
        void unsupportedVersion() {
            String raw = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04|C5|P|2.2\r";
            Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parse(raw));
            assertEquals(Hl7ParseException.ErrorCode.UNSUPPORTED_VERSION, ex.errorCode());
            assertEquals("MSH-12.1", ex.location());
        }

        @Test
        @DisplayName("用例: 缺少MSH-12 -> 结果: MISSING_FIELD")
        void missingVersion() {
            String raw = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04|C6|P|\r";
            Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parse(raw));
            assertEquals(Hl7ParseException.ErrorCode.MISSING_FIELD, ex.errorCode());
        }

        @Test
        @DisplayName("用例: \\n 与 \\r\\n 行结束混用 -> 结果: 与 \\r 等价解析")
        void mixedLineEndings() {
            String raw = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04|C7|P|2.5\n"
                    + "PID|1||P1||Doe^John||19800101|M\n";
            Hl7Message message = parser.parse(raw.getBytes(StandardCharsets.UTF_8));
            assertEquals("John", message.segment("PID").value(5, 1, 2));
        }
    }
}
