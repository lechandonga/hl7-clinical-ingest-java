package com.github.lechandonga.hl7clinicalingestjava.hl7;

import com.github.lechandonga.hl7clinicalingestjava.domain.IncomingEvent;
import com.github.lechandonga.hl7clinicalingestjava.domain.VisitStatus;
import com.github.lechandonga.hl7clinicalingestjava.security.Masking;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 将解析后的 HL7 消息规范化为与版本无关的 {@link IncomingEvent}。
 *
 * <p>重点兼容的 2.3 / 2.5 差异：
 * <ul>
 *   <li>MSH-9：2.3 为 {@code ADT^A01}（消息类型^触发事件）；2.5 为
 *       {@code ADT^A01^ADT_A01}（第三段是消息结构码）。</li>
 *   <li>PID-3 标识符类型：2.3 取第 5 个组件，2.5 取标识符类型子组件（CX-5.3）。</li>
 *   <li>EVN 段在 2.3 下经常缺省；业务时间回退顺序 EVN-2 → PV1-44 → MSH-7。</li>
 *   <li>A02/A03 的转出信息（PV1-6/PV1-45）存在时优先作为业务时间与位置来源。</li>
 * </ul>
 */
public final class AdtNormalizer {

    private static final Map<String, VisitStatus> SUPPORTED_TRIGGERS = Map.of(
            "A01", VisitStatus.ADMITTED,
            "A02", VisitStatus.TRANSFERRED,
            "A03", VisitStatus.DISCHARGED,
            "A04", VisitStatus.REGISTERED,
            "A08", VisitStatus.UPDATED
    );

    private static final Set<String> DISCHARGE_OR_TRANSFER = Set.of("A02", "A03");

    public IncomingEvent normalize(Hl7Message message, String rawMessage) {
        Hl7Message.Segment msh = requireSegment(message, "MSH");
        String messageType = required(msh, 9, 1, "MSH-9.1 message type");
        String triggerEvent;
        if ("2.3".equals(message.version()) || "2.3.1".equals(message.version())) {
            triggerEvent = required(msh, 9, 2, "MSH-9.2 trigger event");
        } else {
            triggerEvent = required(msh, 9, 2, "MSH-9.2 trigger event");
            // 2.4+ MSH-9.3 为 message structure；此处不强制，但 ACK 构造使用相同规则。
        }
        if (!"ADT".equals(messageType) || !SUPPORTED_TRIGGERS.containsKey(triggerEvent)) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.UNSUPPORTED_MESSAGE_TYPE,
                    "MSH-9", messageType + "^" + triggerEvent + " is not supported");
        }

        String controlId = required(msh, 10, 1, "MSH-10 message control id");
        OffsetDateTime messageTime = Hl7DateTime.parse(msh.value(7), "MSH-7");

        Hl7Message.Segment pid = requireSegment(message, "PID");
        String patientId = firstNonBlank(pid.field(3) == null ? null : pid.field(3).repetitionValues());
        if (patientId == null) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD, "PID-3",
                    "patient identifier list is empty");
        }
        // 2.3: CX-5 为 IS（标识符类型码本身，等价于子组件1）；
        // 2.5: CX-5 为 CWE，类型码在 CWE-1（即子组件1），CWE-3 为编码体系名称。
        String idType = pid.value(3, 1, 5, 1);

        String familyName = pid.value(5, 1, 1);
        String givenName = pid.value(5, 1, 2);
        String fullName = joinName(familyName, givenName);
        if (fullName == null) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD, "PID-5",
                    "patient name is required");
        }

        String phone = null;
        Hl7Message.Field phoneField = pid.field(13);
        if (phoneField != null) {
            for (int r = 1; r <= phoneField.repetitionCount(); r++) {
                // 2.5 新格式号码在 XTN-7；2.3 老格式号码直接在 XTN-1。
                String candidate = pid.value(13, r, 7);
                if (candidate == null || candidate.isBlank()) {
                    candidate = pid.value(13, r, 6);
                }
                if (candidate == null || candidate.isBlank()) {
                    String oldStyle = pid.value(13, r, 1);
                    if (oldStyle != null && !oldStyle.isBlank()
                            && oldStyle.chars().anyMatch(Character::isDigit)) {
                        candidate = oldStyle;
                    }
                }
                if (candidate != null && !candidate.isBlank()) {
                    phone = candidate;
                    break;
                }
            }
        }

        Hl7Message.Segment evn = message.segment("EVN");
        Hl7Message.Segment pv1 = message.segment("PV1");

        String visitNumber = pv1 == null ? null : pv1.value(19, 1, 1);
        if (visitNumber == null || visitNumber.isBlank()) {
            // 确定性回退：报文未给就诊号时，同一发送方+同一患者的多次事件落在同一就诊。
            String senderFacility = msh.value(4, 1) == null ? "" : msh.value(4, 1).trim();
            visitNumber = "AUTO-VISIT#" + Masking.shortHash(senderFacility + "|" + patientId);
            if (pv1 == null && !"A04".equals(triggerEvent) && !"A08".equals(triggerEvent)) {
                throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD, "PV1",
                        "PV1 segment is required for trigger " + triggerEvent);
            }
        }

        String patientClass = pv1 == null ? null : pv1.value(2);

        OffsetDateTime eventTime = null;
        String location = null;
        if (evn != null) {
            String evn2 = evn.value(2);
            if (evn2 != null && !evn2.isBlank()) {
                eventTime = Hl7DateTime.parse(evn2, "EVN-2");
            }
        }
        if (pv1 != null && DISCHARGE_OR_TRANSFER.contains(triggerEvent)) {
            String pv145 = pv1.value(45);
            if (pv145 != null && !pv145.isBlank()) {
                eventTime = Hl7DateTime.parse(pv145, "PV1-45");
            }
            location = assembledLocation(pv1, 6);
        }
        if (pv1 != null && eventTime == null) {
            String pv144 = pv1.value(44);
            if (pv144 != null && !pv144.isBlank()) {
                eventTime = Hl7DateTime.parse(pv144, "PV1-44");
            }
        }
        if (pv1 != null && location == null) {
            location = assembledLocation(pv1, 3);
        }
        if (eventTime == null) {
            // 业务时间全部缺省时回退到报文时间（MSH-7），并由合并层明确记录这一判定。
            eventTime = messageTime;
        }

        return new IncomingEvent(
                controlId,
                message.version(),
                messageType,
                triggerEvent,
                msh.value(3, 1),
                msh.value(4, 1),
                patientId.trim(),
                idType,
                fullName,
                familyName,
                givenName,
                pid.value(7),
                pid.value(8),
                phone,
                visitNumber.trim(),
                patientClass,
                location,
                SUPPORTED_TRIGGERS.get(triggerEvent),
                eventTime,
                messageTime,
                Masking.sha256(rawMessage == null ? "" : rawMessage)
        );
    }

    private Hl7Message.Segment requireSegment(Hl7Message message, String name) {
        Hl7Message.Segment segment = message.segment(name);
        if (segment == null) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD, name,
                    name + " segment is required");
        }
        return segment;
    }

    private String required(Hl7Message.Segment segment, int field, int component, String what) {
        String value = component <= 1 ? segment.value(field) : segment.value(field, component);
        if (value == null || value.isBlank()) {
            throw new Hl7ParseException(Hl7ParseException.ErrorCode.MISSING_FIELD,
                    segment.name() + "-" + field, what + " is required");
        }
        return value.trim();
    }

    private String firstNonBlank(java.util.List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String joinName(String family, String given) {
        boolean f = family != null && !family.isBlank();
        boolean g = given != null && !given.isBlank();
        if (f && g) {
            return family.trim() + "^" + given.trim();
        }
        if (f) {
            return family.trim();
        }
        return g ? given.trim() : null;
    }

    /** PL 复合位置：点^房^床^设施（缺失组件自动跳过）。 */
    private String assembledLocation(Hl7Message.Segment pv1, int field) {
        Hl7Message.Field f = pv1.field(field);
        if (f == null) {
            return null;
        }
        String point = pv1.value(field, 1, 1);
        String room = pv1.value(field, 1, 2);
        String bed = pv1.value(field, 1, 3);
        String facility = pv1.value(field, 1, 4);
        StringBuilder sb = new StringBuilder();
        appendPart(sb, point);
        appendPart(sb, room);
        appendPart(sb, bed);
        appendPart(sb, facility);
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('/');
        }
        sb.append(part.trim());
    }
}
