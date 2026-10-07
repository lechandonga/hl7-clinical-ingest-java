package com.github.lechandonga.hl7clinicalingestjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：ACK 形态、解析失败也回 200+AE、对外查询无 PHI 原文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Hl7ControllerWebTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("业务: 正常接入确认 | 用例: 推送2.3 A04 | 结果: 返回AA ACK且可查询脱敏就诊")
    void acceptedAckAndMaskedQuery() {
        String adt = "MSH|^~\\&|SENDER|FAC-WEB|RECV|LOCAL|20240101120000||ADT^A04|WEB-1|P|2.3\r"
                + "EVN|A04|20240101120000\r"
                + "PID|1||MR-7777^^^MR||Secret^Patient||19770707|F|||||^HOME^PH^^^^13800001234\r"
                + "PV1|1|O|OPD^R1|||||||||||||VW-1\r";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        String ack = rest.postForObject(url("/hl7/messages"),
                new HttpEntity<>(adt, headers), String.class);

        assertTrue(ack.contains("MSH|"), "ACK 必须是 HL7 报文");
        assertTrue(ack.contains("MSA|AA|WEB-1"), "AA 确认应回放控制号");

        String patients = rest.getForObject(url("/api/patients"), String.class);
        String visits = rest.getForObject(url("/api/visits"), String.class);
        assertFalse(patients.contains("Secret"), "姓名原文不得出现在响应中");
        assertFalse(patients.contains("Patient"), "姓名原文不得出现在响应中");
        assertFalse(patients.contains("MR-7777"), "证件号原文不得出现在响应中");
        assertFalse(patients.contains("13800001234"), "电话原文不得出现在响应中");
        assertFalse(visits.contains("VW-1"), "就诊号原文不得出现在响应中");
        assertTrue(patients.contains("7777#"), "应保留后4位+哈希的关联标识");
    }

    @Test
    @DisplayName("业务: 解析失败确认 | 用例: 推送损坏报文 | 结果: HTTP200且MSA=AE,错误类型可区分")
    void parseFailureReturnsAeNot500() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        String response = rest.postForObject(url("/hl7/messages"),
                new HttpEntity<>("NOT-HL7-AT-ALL", headers), String.class);
        assertTrue(response.contains("MSA|AE"), "损坏报文必须返回 AE 而非服务器异常");
        assertTrue(response.contains("HL7-1002"), "必须包含可区分错误码");
    }

    @Test
    @DisplayName("业务: 2.5错误确认形态 | 用例: 非法转义 | 结果: ERR含ERL位置与HL70357条件码")
    void version25ErrShape() {
        String adt = "MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04^ADT_A04|WEB-BAD|P|2.5\r"
                + "PID|1||MR-9||bad\\Q\\x||19800101|M\r";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "hl7-v2",
                StandardCharsets.UTF_8));
        String ack = rest.postForObject(url("/hl7/messages"),
                new HttpEntity<>(adt, headers), String.class);
        assertTrue(ack.startsWith("MSH|^~\\&"), "应回显与入站兼容的分隔符");
        assertTrue(ack.contains("MSA|AE|WEB-BAD"));
        assertTrue(ack.contains("HL70357"), "2.5 ERR-2 使用 HL70357 代码体系");
        assertTrue(ack.contains("PID-5"), "ERR 必须带出错位置");
    }
}
