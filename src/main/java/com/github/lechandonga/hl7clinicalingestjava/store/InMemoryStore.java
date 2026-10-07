package com.github.lechandonga.hl7clinicalingestjava.store;

import com.github.lechandonga.hl7clinicalingestjava.domain.ConflictRecord;
import com.github.lechandonga.hl7clinicalingestjava.domain.Patient;
import com.github.lechandonga.hl7clinicalingestjava.domain.Visit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内存储。所有写操作通过单一锁串行化，保证并发上报的合并结论稳定一致；
 * 重启后数据清空，符合“本地复现、不接数据库”的要求。
 */
@Component
public class InMemoryStore {

    private final Object lock = new Object();

    private final Map<String, Patient> patients = new LinkedHashMap<>();
    private final Map<String, Visit> visits = new LinkedHashMap<>();
    private final Map<String, ProcessedMessage> processedMessages = new LinkedHashMap<>();
    private final List<ConflictRecord> conflicts = new ArrayList<>();

    public Object lock() {
        return lock;
    }

    public Patient patient(String key) {
        return patients.get(key);
    }

    public Patient putPatientIfAbsent(String key, Patient patient) {
        return patients.putIfAbsent(key, patient);
    }

    public Visit visit(String key) {
        return visits.get(key);
    }

    public void putVisit(String key, Visit visit) {
        visits.put(key, visit);
    }

    public Optional<ProcessedMessage> processed(String idempotencyKey) {
        return Optional.ofNullable(processedMessages.get(idempotencyKey));
    }

    public void remember(ProcessedMessage record) {
        processedMessages.put(record.idempotencyKey(), record);
    }

    public void addConflict(ConflictRecord record) {
        conflicts.add(record);
    }

    public List<Patient> listPatients() {
        return List.copyOf(patients.values());
    }

    public List<Visit> listVisits() {
        return List.copyOf(visits.values());
    }

    public List<ConflictRecord> listConflicts() {
        return List.copyOf(conflicts);
    }

    public List<ProcessedMessage> listProcessed() {
        return List.copyOf(processedMessages.values());
    }

    /** 已处理消息的幂等记录。 */
    public record ProcessedMessage(
            String idempotencyKey,
            String controlId,
            String senderFacility,
            String rawHash,
            String outcome,
            String visitKey,
            long receivedAtEpochMilli
    ) {
    }
}
