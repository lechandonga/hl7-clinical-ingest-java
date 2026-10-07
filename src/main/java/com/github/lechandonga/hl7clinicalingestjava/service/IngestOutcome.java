package com.github.lechandonga.hl7clinicalingestjava.service;

import com.github.lechandonga.hl7clinicalingestjava.hl7.Hl7ParseException;

/**
 * 接入结果。ACK 状态语义：
 * AA = 接受（含首次应用 / 重复幂等 / 乱序拒收但语义确认），
 * AE = 拒绝（解析、校验或不支持的版本/类型）。
 */
public record IngestOutcome(
        AckStatus ackStatus,
        Disposition disposition,
        String controlId,
        String version,
        String visitKey,
        String patientRef,
        String correlationId,
        Hl7ParseException.ErrorCode errorCode,
        String errorLocation,
        String errorDetail
) {
    public enum AckStatus {
        AA, AE
    }

    public enum Disposition {
        ACCEPTED, DUPLICATE, STALE, CONFLICT_RESOLVED, REJECTED
    }

    public static IngestOutcome accepted(String controlId, String version, String visitKey,
                                         String patientRef, String correlationId) {
        return new IngestOutcome(AckStatus.AA, Disposition.ACCEPTED, controlId, version, visitKey,
                patientRef, correlationId, null, null, null);
    }

    public static IngestOutcome duplicate(String controlId, String version, String visitKey,
                                          String patientRef, String correlationId) {
        return new IngestOutcome(AckStatus.AA, Disposition.DUPLICATE, controlId, version, visitKey,
                patientRef, correlationId, null, null, null);
    }

    public static IngestOutcome stale(String controlId, String version, String visitKey,
                                      String patientRef, String correlationId) {
        return new IngestOutcome(AckStatus.AA, Disposition.STALE, controlId, version, visitKey,
                patientRef, correlationId, null, null, null);
    }

    public static IngestOutcome resolved(String controlId, String version, String visitKey,
                                         String patientRef, String correlationId) {
        return new IngestOutcome(AckStatus.AA, Disposition.CONFLICT_RESOLVED, controlId, version,
                visitKey, patientRef, correlationId, null, null, null);
    }

    public static IngestOutcome rejected(String controlId, String version, String correlationId,
                                         Hl7ParseException.ErrorCode code, String location,
                                         String detail) {
        return new IngestOutcome(AckStatus.AE, Disposition.REJECTED, controlId, version, null, null,
                correlationId, code, location, detail);
    }
}
