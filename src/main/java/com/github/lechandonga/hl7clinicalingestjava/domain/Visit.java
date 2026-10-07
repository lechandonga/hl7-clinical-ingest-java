package com.github.lechandonga.hl7clinicalingestjava.domain;

import java.time.OffsetDateTime;

/** 就诊聚合：状态按业务时间（EVN-2/PV1-44,45/MSH-7）合并。 */
public class Visit {
    private final String visitKey;
    private final String patientKey;
    private String visitNumber;
    private VisitStatus status;
    private String patientClass;
    private String location;
    private OffsetDateTime businessTime;
    private String winningControlId;
    private long version = 0;

    public Visit(String visitKey, String patientKey) {
        this.visitKey = visitKey;
        this.patientKey = patientKey;
    }

    public String visitKey() {
        return visitKey;
    }

    public String patientKey() {
        return patientKey;
    }

    public String visitNumber() {
        return visitNumber;
    }

    public VisitStatus status() {
        return status;
    }

    public String patientClass() {
        return patientClass;
    }

    public String location() {
        return location;
    }

    public OffsetDateTime businessTime() {
        return businessTime;
    }

    public String winningControlId() {
        return winningControlId;
    }

    public long version() {
        return version;
    }

    public void apply(IncomingEvent event) {
        this.visitNumber = event.visitNumber();
        this.status = event.visitStatus();
        this.patientClass = event.patientClass();
        if (event.location() != null) {
            this.location = event.location();
        }
        this.businessTime = event.eventTime();
        this.winningControlId = event.messageControlId();
        this.version++;
    }
}
