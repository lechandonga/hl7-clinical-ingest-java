package com.github.lechandonga.hl7clinicalingestjava.tests;

import com.github.lechandonga.hl7clinicalingestjava.domain.ConflictRecord;
import com.github.lechandonga.hl7clinicalingestjava.domain.Visit;
import com.github.lechandonga.hl7clinicalingestjava.service.IngestOutcome;
import com.github.lechandonga.hl7clinicalingestjava.service.IngestService;
import com.github.lechandonga.hl7clinicalingestjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务合并用例：幂等、乱序拒收、同业务时间冲突裁决、并发确定性、失败确认。
 */
class IngestServiceTest {

    private InMemoryStore store;
    private IngestService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new IngestService(store);
    }

    private byte[] adt(String version, String trigger, String controlId, String eventTime,
                       String visitNumber, String statusTimeField) {
        String msh9 = version.startsWith("2.3") ? "ADT^" + trigger
                : "ADT^" + trigger + "^ADT_" + trigger;
        String[] pv1 = new String[46];
        pv1[0] = "PV1";
        pv1[1] = "1";
        pv1[2] = "I";
        pv1[3] = "WARD1^ROOM1^BED1^FAC1";
        pv1[19] = visitNumber;
        pv1[44] = statusTimeField;
        pv1[45] = statusTimeField;
        StringBuilder pv1Line = new StringBuilder();
        for (int i = 0; i < pv1.length; i++) {
            if (i > 0) {
                pv1Line.append('|');
            }
            if (pv1[i] != null) {
                pv1Line.append(pv1[i]);
            }
        }
        String raw = "MSH|^~\\&|SENDER|FAC1|RECV|LOCAL|" + eventTime + "||" + msh9 + "|"
                + controlId + "|P|" + version + "\r"
                + "EVN|" + trigger + "|" + eventTime + "\r"
                + "PID|1||MR-1001^^^MR||Zhang^San||19800101|M|||555-1234\r"
                + pv1Line + "\r";
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private Visit onlyVisit() {
        List<Visit> visits = store.listVisits();
        assertEquals(1, visits.size(), "就诊数量必须始终为1");
        return visits.get(0);
    }

    @Nested
    @DisplayName("业务: 消息幂等")
    class Idempotency {

        @Test
        @DisplayName("用例: 同发送方重复推送同一A01 -> 结果: 第二次DUPLICATE且无重复就诊")
        void exactDuplicate() {
            byte[] msg = adt("2.5", "A01", "CTL-DUP", "20240102100000", "V1",
                    "20240102100000");
            IngestOutcome first = service.ingest(msg);
            IngestOutcome second = service.ingest(msg);
            assertEquals(IngestOutcome.AckStatus.AA, first.ackStatus());
            assertEquals(IngestOutcome.Disposition.ACCEPTED, first.disposition());
            assertEquals(IngestOutcome.Disposition.DUPLICATE, second.disposition());
            onlyVisit();
            assertEquals(1, onlyVisit().version());
        }

        @Test
        @DisplayName("用例: 同控制号不同报文内容 -> 结果: AE拒绝并记录IDEMPOTENCY_MISMATCH")
        void controlIdReuse() {
            byte[] first = adt("2.5", "A01", "CTL-REUSE", "20240102100000", "V1",
                    "20240102100000");
            byte[] second = adt("2.5", "A02", "CTL-REUSE", "20240102110000", "V1",
                    "20240102110000");
            service.ingest(first);
            IngestOutcome outcome = service.ingest(second);
            assertEquals(IngestOutcome.AckStatus.AE, outcome.ackStatus());
            assertTrue(store.listConflicts().stream()
                    .anyMatch(c -> ConflictRecord.IDEMPOTENCY_MISMATCH.equals(c.decision())));
        }
    }

    @Nested
    @DisplayName("业务: 按业务时间合并")
    class Merge {

        @Test
        @DisplayName("用例: 先A03出院(晚)后A01入院(早)乱序 -> 结果: 旧状态不覆盖,STALE")
        void outOfOrder() {
            service.ingest(adt("2.3", "A03", "CTL-LATE", "20240103180000", "V1",
                    "20240103180000"));
            IngestOutcome stale = service.ingest(adt("2.3", "A01", "CTL-EARLY",
                    "20240101080000", "V1", "20240101080000"));
            assertEquals(IngestOutcome.Disposition.STALE, stale.disposition());
            assertEquals("discharged", onlyVisit().status().code());
            assertTrue(store.listConflicts().stream()
                    .anyMatch(c -> ConflictRecord.STALE_REJECTED.equals(c.decision())));
        }

        @Test
        @DisplayName("用例: 正常顺序 A01->A02->A03 -> 结果: 状态逐步演进到discharged")
        void orderedLifecycle() {
            service.ingest(adt("2.5", "A01", "CTL-A01", "20240101080000", "V1",
                    "20240101080000"));
            service.ingest(adt("2.5", "A02", "CTL-A02", "20240102090000", "V1",
                    "20240102090000"));
            IngestOutcome discharge = service.ingest(adt("2.5", "A03", "CTL-A03",
                    "20240103100000", "V1", "20240103100000"));
            assertEquals(IngestOutcome.Disposition.ACCEPTED, discharge.disposition());
            assertEquals("discharged", onlyVisit().status().code());
            assertEquals(3, onlyVisit().version());
        }

        @Test
        @DisplayName("用例: 同业务时间 A01与A03并发竞争 -> 结果: discharged按优先级确定性获胜")
        void sameBusinessTimePrecedence() {
            IngestOutcome a01 = service.ingest(adt("2.5", "A01", "CTL-T-A01",
                    "20240101100000", "V1", "20240101100000"));
            IngestOutcome a03 = service.ingest(adt("2.5", "A03", "CTL-T-A03",
                    "20240101100000", "V1", "20240101100000"));
            assertEquals(IngestOutcome.Disposition.ACCEPTED, a01.disposition());
            assertEquals(IngestOutcome.Disposition.CONFLICT_RESOLVED, a03.disposition());
            assertEquals("discharged", onlyVisit().status().code());
            assertTrue(store.listConflicts().stream()
                    .anyMatch(c -> ConflictRecord.SAME_TIME_RESOLVED.equals(c.decision())));
        }

        @Test
        @DisplayName("用例: 8线程并发推送不同时间/重复消息 -> 结果: 结论串行稳定,仅1条就诊")
        void concurrentPushes() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Callable<IngestOutcome>> tasks = new ArrayList<>();
            String[] controls = {"C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8"};
            for (int i = 0; i < controls.length; i++) {
                String ctl = controls[i];
                String time = String.format("20240101%02d0000", 8 + i);
                byte[] msg = adt("2.5", "A08", ctl, time, "V1", time);
                tasks.add(() -> service.ingest(msg));
                tasks.add(() -> service.ingest(msg));
            }
            java.util.List<IngestOutcome> outcomes = new ArrayList<>();
            for (Future<IngestOutcome> future : pool.invokeAll(tasks)) {
                outcomes.add(future.get());
            }
            pool.shutdown();
            onlyVisit();
            // 并发下到达顺序任意：最终获胜者必须是最晚业务时间 C8（与到达顺序无关），
            // 较早的消息可能被判定 STALE，因此 version 落在 [1,8]，这是确定性行为。
            assertEquals("C8", onlyVisit().winningControlId());
            assertEquals("2024-01-01T15:00Z", onlyVisit().businessTime().toString());
            assertTrue(onlyVisit().version() >= 1 && onlyVisit().version() <= 8,
                    "版本号应在[1,8]之间，实际: " + onlyVisit().version());
            assertEquals(8, store.listProcessed().size(), "8条不同消息必须各有一条幂等记录");
            long duplicates = outcomes.stream()
                    .filter(o -> o.disposition() == IngestOutcome.Disposition.DUPLICATE)
                    .count();
            assertEquals(8, duplicates, "每条消息的第二次推送必须是幂等DUPLICATE");
        }
    }

    @Nested
    @DisplayName("业务: 失败确认")
    class Failure {

        @Test
        @DisplayName("用例: 版本2.2报文 -> 结果: AE确认,错误类型UNSUPPORTED_VERSION,无就诊产生")
        void rejectedVersion() {
            byte[] msg = ("MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04|BAD-VER|P|2.2\r"
                    + "PID|1||P1||Doe^John\r").getBytes(StandardCharsets.UTF_8);
            IngestOutcome outcome = service.ingest(msg);
            assertEquals(IngestOutcome.AckStatus.AE, outcome.ackStatus());
            assertEquals("HL7-1007", outcome.errorCode().code());
            assertTrue(store.listVisits().isEmpty());
        }

        @Test
        @DisplayName("用例: 缺少PID-3 -> 结果: AE,错误位置PID-3")
        void missingPidId() {
            byte[] msg = ("MSH|^~\\&|S|F|R|L|20240101120000||ADT^A04^ADT_A04|NO-PID|P|2.5\r"
                    + "PID|1||||Doe^John||19800101|M\r"
                    + "PV1|1|O\r").getBytes(StandardCharsets.UTF_8);
            IngestOutcome outcome = service.ingest(msg);
            assertEquals(IngestOutcome.AckStatus.AE, outcome.ackStatus());
            assertEquals("PID-3", outcome.errorLocation());
        }

        @Test
        @DisplayName("用例: 不支持的触发事件ORM -> 结果: AE,UNSUPPORTED_MESSAGE_TYPE")
        void unsupportedTrigger() {
            byte[] msg = ("MSH|^~\\&|S|F|R|L|20240101120000||ORM^O01^ORM_O01|ORM-1|P|2.5\r"
                    + "PID|1||P1||Doe^John\r").getBytes(StandardCharsets.UTF_8);
            IngestOutcome outcome = service.ingest(msg);
            assertEquals(IngestOutcome.AckStatus.AE, outcome.ackStatus());
            assertEquals("HL7-1010", outcome.errorCode().code());
        }
    }
}
