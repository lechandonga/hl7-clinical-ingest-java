package com.github.lechandonga.hl7clinicalingestjava.domain;

import java.time.OffsetDateTime;

/**
 * 合并冲突判定记录：乱序覆盖被拒绝、同业务时刻的状态竞争、幂等键碰撞
 * 都必须可查（见 /api/conflicts）。
 */
public record ConflictRecord(
        String visitKey,
        String incomingControlId,
        String existingControlId,
        String incomingStatus,
        String existingStatus,
        String decision,
        String reason,
        OffsetDateTime detectedAt
) {
    public static final String STALE_REJECTED = "STALE_REJECTED";
    public static final String SAME_TIME_RESOLVED = "SAME_TIME_RESOLVED";
    public static final String IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH";
}
