package com.github.lechandonga.hl7clinicalingestjava.domain;

/** 就诊归一化状态：以状态机顺序决定同一业务时刻的确定性冲突裁决。 */
public enum VisitStatus {
    REGISTERED("registered", 10),
    ADMITTED("admitted", 30),
    TRANSFERRED("transferred", 20),
    UPDATED("updated", 15),
    DISCHARGED("discharged", 40);

    private final String code;
    private final int precedence;

    VisitStatus(String code, int precedence) {
        this.code = code;
        this.precedence = precedence;
    }

    public String code() {
        return code;
    }

    /** 同一业务时间乱序/并发时，状态机越靠后的状态优先级越高。 */
    public int precedence() {
        return precedence;
    }
}
