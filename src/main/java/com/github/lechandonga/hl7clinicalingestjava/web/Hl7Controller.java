package com.github.lechandonga.hl7clinicalingestjava.web;

import com.github.lechandonga.hl7clinicalingestjava.ack.AckBuilder;
import com.github.lechandonga.hl7clinicalingestjava.domain.ConflictRecord;
import com.github.lechandonga.hl7clinicalingestjava.domain.Patient;
import com.github.lechandonga.hl7clinicalingestjava.domain.Visit;
import com.github.lechandonga.hl7clinicalingestjava.security.Masking;
import com.github.lechandonga.hl7clinicalingestjava.service.IngestOutcome;
import com.github.lechandonga.hl7clinicalingestjava.service.IngestService;
import com.github.lechandonga.hl7clinicalingestjava.store.InMemoryStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP 入口。即使消息解析失败也返回 HTTP 200 + AE ACK（HL7 应用层语义），
 * 绝不向调用方抛 5xx；所有返回 DTO 均为脱敏视图。
 */
@RestController
@RequestMapping
public class Hl7Controller {

    private final IngestService ingestService;
    private final InMemoryStore store;

    public Hl7Controller(IngestService ingestService, InMemoryStore store) {
        this.ingestService = ingestService;
        this.store = store;
    }

    @PostMapping(path = "/hl7/messages",
            consumes = {MediaType.TEXT_PLAIN_VALUE, "application/hl7-v2", MediaType.ALL_VALUE})
    public ResponseEntity<byte[]> ingest(@RequestBody(required = false) byte[] body) {
        IngestOutcome outcome = ingestService.ingest(body);
        String ack = AckBuilder.build(outcome);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/hl7-v2; charset=utf-8"))
                .body(ack.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/patients")
    public List<PatientView> patients() {
        return store.listPatients().stream().map(PatientView::from).toList();
    }

    @GetMapping("/api/visits")
    public List<VisitView> visits() {
        return store.listVisits().stream().map(VisitView::from).toList();
    }

    @GetMapping("/api/messages")
    public List<InMemoryStore.ProcessedMessage> messages() {
        return store.listProcessed();
    }

    @GetMapping("/api/conflicts")
    public List<ConflictRecord> conflicts() {
        return store.listConflicts();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> unexpected(Exception ex) {
        // 兜底：任何未预期异常也不泄露堆栈/PHI，返回结构化失败确认。
        IngestOutcome outcome = IngestOutcome.rejected("UNKNOWN", null,
                Masking.shortHash(Masking.sha256(ex.toString())),
                com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7ParseException.ErrorCode.INTERNAL_ERROR,
                null, "internal processing error");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/hl7-v2; charset=utf-8"))
                .body(AckBuilder.build(outcome).getBytes(StandardCharsets.UTF_8));
    }

    public record PatientView(String patientKey, String patientRef, String idType, String name, String dateOfBirth,
                              String sex, String phone, String lastControlId, String updatedAt) {
        static PatientView from(Patient p) {
            String name = null;
            if (p.familyName() != null || p.givenName() != null) {
                name = Masking.maskName(join(p.familyName(), p.givenName()));
            }
            return new PatientView(
                    p.patientKey(),
                    Masking.maskIdentifier(p.patientId()),
                    p.patientIdType(),
                    name,
                    Masking.maskDateOfBirth(p.dateOfBirth()),
                    p.administrativeSex(),
                    Masking.maskPhone(p.phone()),
                    p.lastControlId(),
                    p.updatedAt() == null ? null : p.updatedAt().toString());
        }

        private static String join(String family, String given) {
            if (family != null && given != null) {
                return family + "^" + given;
            }
            return family != null ? family : given;
        }
    }

    public record VisitView(String visitRef, String patientRef, String visitNumber, String status,
                            String patientClass, String location, String businessTime,
                            String winningControlId, long version) {
        static VisitView from(Visit v) {
            return new VisitView(
                    Masking.maskIdentifier(v.visitKey()),
                    v.patientKey(),
                    Masking.maskIdentifier(v.visitNumber()),
                    v.status().code(),
                    v.patientClass(),
                    v.location(),
                    v.businessTime() == null ? null : v.businessTime().toString(),
                    v.winningControlId(),
                    v.version());
        }
    }
}
