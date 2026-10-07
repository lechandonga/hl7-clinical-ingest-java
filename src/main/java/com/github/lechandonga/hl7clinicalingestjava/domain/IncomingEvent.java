package com.github.lechandonga.hl7clinicalingestjava.domain;

import java.time.OffsetDateTime;

/**
 * 从一条 HL7 ADT 报文中规范化得到的业务事件，与版本无关。
 * 原始姓名/证件/电话仅在服务端内存中短暂使用；对外输出统一走 Masking。
 */
public record IncomingEvent(
        String messageControlId,
        String version,
        String messageType,
        String triggerEvent,
        String senderApplication,
        String senderFacility,
        String patientId,
        String patientIdType,
        String patientName,
        String patientFamilyName,
        String patientGivenName,
        String dateOfBirth,
        String administrativeSex,
        String phone,
        String visitNumber,
        String patientClass,
        String location,
        VisitStatus visitStatus,
        OffsetDateTime eventTime,
        OffsetDateTime messageTime,
        String rawMessageHash
) {
}
