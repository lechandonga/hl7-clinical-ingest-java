package com.github.lechandonga.hl7clinicalingestjava.domain;

/** 患者聚合。原始姓名/证件/电话仅存内存，对外读取必须经过 Masking。 */
public class Patient {
    private final String patientKey;
    private String patientId;
    private String patientIdType;
    private String familyName;
    private String givenName;
    private String dateOfBirth;
    private String administrativeSex;
    private String phone;
    private String lastControlId;
    private OffsetDateTimeHolder updatedAt = new OffsetDateTimeHolder();

    public Patient(String patientKey) {
        this.patientKey = patientKey;
    }

    public String patientKey() {
        return patientKey;
    }

    public String patientId() {
        return patientId;
    }

    public String patientIdType() {
        return patientIdType;
    }

    public String familyName() {
        return familyName;
    }

    public String givenName() {
        return givenName;
    }

    public String dateOfBirth() {
        return dateOfBirth;
    }

    public String administrativeSex() {
        return administrativeSex;
    }

    public String phone() {
        return phone;
    }

    public String lastControlId() {
        return lastControlId;
    }

    public java.time.OffsetDateTime updatedAt() {
        return updatedAt.value;
    }

    public void apply(IncomingEvent event, java.time.OffsetDateTime now) {
        this.patientId = event.patientId();
        this.patientIdType = event.patientIdType();
        this.familyName = event.patientFamilyName();
        this.givenName = event.patientGivenName();
        this.dateOfBirth = event.dateOfBirth();
        this.administrativeSex = event.administrativeSex();
        if (event.phone() != null) {
            this.phone = event.phone();
        }
        this.lastControlId = event.messageControlId();
        this.updatedAt.value = now;
    }

    private static final class OffsetDateTimeHolder {
        private java.time.OffsetDateTime value;
    }
}
