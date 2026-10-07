package com.github.lechandonga.hl7clinicalingestjava.service;

import com.github.lechandonga.hl7clinicalingestjava.domain.ConflictRecord;
import com.github.lechandonga.hl7clinicalingestjava.domain.IncomingEvent;
import com.github.lechandonga.hl7clinicalingestjava.domain.Patient;
import com.github.lechandonga.hl7clinicalingestjava.domain.Visit;
import com.github.lechandonga.hl7clinicalingestjava.hl7.AdtNormalizer;
import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7Message;
import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7ParseException;
import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7Parser;
import com.github.lechandonga.hl7clinicalingestjava.security.Masking;
import com.github.lechandonga.hl7clinicalingestjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * 消息接入核心：解析 → 规范化 → 幂等判定 → 业务时间合并。
 * 关键规则：
 * <ol>
 *   <li>幂等键 = 发送方设施|发送方应用|MSH-10 控制号；同键且原文哈希相同 → 纯重复；
 *       同键但哈希不同 → 记录 IDEMPOTENCY_MISMATCH 冲突并拒绝（控制号被复用）。</li>
 *   <li>同一就诊（发送方设施|患者标识|就诊号）按业务时间合并；旧时间不得覆盖新状态。</li>
 *   <li>业务时间相同按状态机优先级（DISCHARGED &gt; ADMITTED &gt; TRANSFERRED &gt;
 *       UPDATED &gt; REGISTERED）裁决，仍相同则控制号字典序兜底，保证并发确定性。</li>
 * </ol>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final Hl7Parser parser = new Hl7Parser();
    private final AdtNormalizer normalizer = new AdtNormalizer();
    private final InMemoryStore store;

    public IngestService(InMemoryStore store) {
        this.store = store;
    }

    public IngestOutcome ingest(byte[] rawBytes) {
        String raw = rawBytes == null ? null : new String(rawBytes, StandardCharsets.UTF_8);
        String rawHash = Masking.sha256(raw == null ? "" : raw);
        String correlation = Masking.shortHash(rawHash);

        Hl7Message message;
        IncomingEvent event;
        try {
            message = parser.parse(rawBytes);
            event = normalizer.normalize(message, raw);
        } catch (Hl7ParseException ex) {
            String controlId = bestEffortControlId(raw);
            String version = bestEffortVersion(raw);
            log.warn("message rejected correlation={} controlId={} code={} location={} detail={}",
                    correlation, controlId, ex.errorCode().code(),
                    ex.location() == null ? "-" : ex.location(),
                    safeDetail(ex));
            return IngestOutcome.rejected(controlId, version, correlation, ex.errorCode(),
                    ex.location(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("unexpected parse failure correlation={}", correlation, ex);
            return IngestOutcome.rejected(bestEffortControlId(raw), bestEffortVersion(raw),
                    correlation, Hl7ParseException.ErrorCode.INTERNAL_ERROR, null, ex.toString());
        }

        synchronized (store.lock()) {
            OffsetDateTime now = OffsetDateTime.now();
            String senderFacility = nz(event.senderFacility());
            String senderApp = nz(event.senderApplication());
            String idempotencyKey = senderFacility + "|" + senderApp + "|" + event.messageControlId();

            var existing = store.processed(idempotencyKey);
            if (existing.isPresent()) {
                var record = existing.get();
                if (record.rawHash().equals(event.rawMessageHash())) {
                    log.info("duplicate message ignored correlation={} controlId={} senderFacilityRef={}",
                            correlation, event.messageControlId(), Masking.maskIdentifier(senderFacility));
                    return IngestOutcome.duplicate(event.messageControlId(), event.version(),
                            record.visitKey(), patientRef(event), correlation);
                }
                store.addConflict(new ConflictRecord(
                        null, event.messageControlId(), record.controlId(),
                        event.visitStatus().code(), "-",
                        ConflictRecord.IDEMPOTENCY_MISMATCH,
                        "same control id reused with different payload", now));
                log.warn("control id reused with different payload correlation={} controlId={}",
                        correlation, event.messageControlId());
                return IngestOutcome.rejected(event.messageControlId(), event.version(),
                        correlation, Hl7ParseException.ErrorCode.MISSING_FIELD, "MSH-10",
                        "message control id reused with different payload");
            }

            String patientKey = patientKey(senderFacility, event.patientId());
            String visitKey = visitKey(senderFacility, event);

            Patient patient = store.patient(patientKey);
            if (patient == null) {
                patient = new Patient(patientKey);
                store.putPatientIfAbsent(patientKey, patient);
                patient = store.patient(patientKey);
            }

            Visit visit = store.visit(visitKey);
            IngestOutcome outcome;
            if (visit == null) {
                visit = new Visit(visitKey, patientKey);
                visit.apply(event);
                store.putVisit(visitKey, visit);
                outcome = IngestOutcome.accepted(event.messageControlId(), event.version(),
                        visitKey, patientRef(event), correlation);
                log.info("visit created correlation={} controlId={} patientRef={} visitRef={} status={} businessTime={}",
                        correlation, event.messageControlId(), patientRef(event),
                        Masking.maskIdentifier(visitKey), event.visitStatus().code(),
                        event.eventTime());
            } else {
                outcome = merge(visit, event, visitKey, patientRef(event), correlation, now);
            }
            patient.apply(event, now);

            store.remember(new InMemoryStore.ProcessedMessage(idempotencyKey,
                    event.messageControlId(), senderFacility, event.rawMessageHash(),
                    outcome.disposition().name(), visitKey, now.toInstant().toEpochMilli()));
            return outcome;
        }
    }

    private IngestOutcome merge(Visit visit, IncomingEvent event, String visitKey,
                                String patientRef, String correlation, OffsetDateTime now) {
        int cmp = event.eventTime().compareTo(visit.businessTime());
        if (cmp < 0) {
            store.addConflict(new ConflictRecord(visitKey, event.messageControlId(),
                    visit.winningControlId(), event.visitStatus().code(),
                    visit.status().code(), ConflictRecord.STALE_REJECTED,
                    "incoming businessTime " + event.eventTime() + " is older than current "
                            + visit.businessTime(), now));
            log.info("stale update rejected correlation={} controlId={} visitRef={} incoming={} current={}",
                    correlation, event.messageControlId(), Masking.maskIdentifier(visitKey),
                    event.eventTime(), visit.businessTime());
            return IngestOutcome.stale(event.messageControlId(), event.version(), visitKey,
                    patientRef, correlation);
        }
        if (cmp == 0) {
            boolean winsByPrecedence = event.visitStatus().precedence() > visit.status().precedence();
            boolean tieBreakByControlId = event.visitStatus().precedence() == visit.status().precedence()
                    && event.messageControlId().compareTo(visit.winningControlId()) < 0;
            if (!winsByPrecedence && !tieBreakByControlId) {
                store.addConflict(new ConflictRecord(visitKey, event.messageControlId(),
                        visit.winningControlId(), event.visitStatus().code(),
                        visit.status().code(), ConflictRecord.SAME_TIME_RESOLVED,
                        "same businessTime; existing status/control id wins by deterministic rule",
                        now));
                log.info("same-time conflict kept current correlation={} controlId={} visitRef={}",
                        correlation, event.messageControlId(), Masking.maskIdentifier(visitKey));
                return IngestOutcome.stale(event.messageControlId(), event.version(), visitKey,
                        patientRef, correlation);
            }
            store.addConflict(new ConflictRecord(visitKey, event.messageControlId(),
                    visit.winningControlId(), event.visitStatus().code(),
                    visit.status().code(), ConflictRecord.SAME_TIME_RESOLVED,
                    "same businessTime; incoming wins by status precedence or control id tie-break",
                    now));
            visit.apply(event);
            log.info("same-time conflict resolved to incoming correlation={} controlId={} visitRef={} status={}",
                    correlation, event.messageControlId(), Masking.maskIdentifier(visitKey),
                    event.visitStatus().code());
            return IngestOutcome.resolved(event.messageControlId(), event.version(), visitKey,
                    patientRef, correlation);
        }
        visit.apply(event);
        log.info("visit merged correlation={} controlId={} visitRef={} status={} businessTime={}",
                correlation, event.messageControlId(), Masking.maskIdentifier(visitKey),
                event.visitStatus().code(), event.eventTime());
        return IngestOutcome.accepted(event.messageControlId(), event.version(), visitKey,
                patientRef, correlation);
    }

    private String patientKey(String senderFacility, String patientId) {
        return "P#" + Masking.sha256(senderFacility + "|" + patientId).substring(0, 16);
    }

    private String visitKey(String senderFacility, IncomingEvent event) {
        return "V#" + Masking.sha256(senderFacility + "|" + event.patientId()
                + "|" + event.visitNumber()).substring(0, 16);
    }

    private String patientRef(IncomingEvent event) {
        return Masking.maskIdentifier(event.patientId());
    }

    private String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeDetail(Hl7ParseException ex) {
        String message = ex.getMessage();
        return message == null ? "-" : message.replaceAll("[\r\n]", " ");
    }

    /** 解析失败时尽力提取 MSH-10，用于 ACK 关联；提取不到返回 UNKNOWN。 */
    String bestEffortControlId(String raw) {
        try {
            if (raw == null || !raw.startsWith("MSH")) {
                return "UNKNOWN";
            }
            char fieldSep = raw.charAt(3);
            String firstLine = raw.split("\\r|\\n", 2)[0];
            String[] fields = firstLine.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
            if (fields.length > 9 && !fields[9].isBlank()) {
                return fields[9];
            }
        } catch (RuntimeException ignored) {
            // 尽力提取，失败则返回 UNKNOWN。
        }
        return "UNKNOWN";
    }

    String bestEffortVersion(String raw) {
        try {
            if (raw == null || !raw.startsWith("MSH")) {
                return null;
            }
            char fieldSep = raw.charAt(3);
            String firstLine = raw.split("\\r|\\n", 2)[0];
            String[] fields = firstLine.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
            if (fields.length > 11) {
                String version = fields[11];
                int component = version.indexOf('^');
                return component > 0 ? version.substring(0, component) : version;
            }
        } catch (RuntimeException ignored) {
            // 尽力提取。
        }
        return null;
    }
}
