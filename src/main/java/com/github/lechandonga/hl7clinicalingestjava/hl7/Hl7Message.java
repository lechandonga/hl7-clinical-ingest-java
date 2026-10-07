package com.github.lechandonga.hl7clinicalingestjava.hl7;

import java.util.List;

/**
 * 解析后的 HL7 v2 消息：保留段 / 字段 / 重复 / 组件 / 子组件的完整层级，
 * 所有文本均已完成反转义（unescape）。
 */
public final class Hl7Message {

    private final char fieldSeparator;
    private final char componentSeparator;
    private final char repetitionSeparator;
    private final char escapeCharacter;
    private final char subcomponentSeparator;
    private final String version;
    private final List<Segment> segments;

    public Hl7Message(char fieldSeparator, char componentSeparator, char repetitionSeparator,
                      char escapeCharacter, char subcomponentSeparator, String version,
                      List<Segment> segments) {
        this.fieldSeparator = fieldSeparator;
        this.componentSeparator = componentSeparator;
        this.repetitionSeparator = repetitionSeparator;
        this.escapeCharacter = escapeCharacter;
        this.subcomponentSeparator = subcomponentSeparator;
        this.version = version;
        this.segments = List.copyOf(segments);
    }

    public char fieldSeparator() {
        return fieldSeparator;
    }

    public char componentSeparator() {
        return componentSeparator;
    }

    public char repetitionSeparator() {
        return repetitionSeparator;
    }

    public char escapeCharacter() {
        return escapeCharacter;
    }

    public char subcomponentSeparator() {
        return subcomponentSeparator;
    }

    public String version() {
        return version;
    }

    public List<Segment> segments() {
        return segments;
    }

    public Segment segment(String name) {
        for (Segment segment : segments) {
            if (segment.name().equals(name)) {
                return segment;
            }
        }
        return null;
    }

    public List<Segment> segments(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).toList();
    }

    /** HL7 段，字段按 HL7 位置（1 起始）访问；MSH-1 固定返回字段分隔符本身。 */
    public static final class Segment {
        private final String name;
        private final int sequence;
        private final List<Field> fields;

        public Segment(String name, int sequence, List<Field> fields) {
            this.name = name;
            this.sequence = sequence;
            this.fields = List.copyOf(fields);
        }

        public String name() {
            return name;
        }

        public int sequence() {
            return sequence;
        }

        public Field field(int position) {
            if (position < 1 || position > fields.size()) {
                return null;
            }
            return fields.get(position - 1);
        }

        public String value(int position) {
            Field field = field(position);
            return field == null ? null : field.value();
        }

        public String value(int position, int component) {
            Field field = field(position);
            return field == null ? null : field.value(component);
        }

        public String value(int position, int repetition, int component) {
            Field field = field(position);
            return field == null ? null : field.value(repetition, component);
        }

        public String value(int position, int repetition, int component, int subcomponent) {
            Field field = field(position);
            return field == null ? null : field.value(repetition, component, subcomponent);
        }
    }

    /**
     * 字段：由若干“重复”组成；一个重复由若干“组件”组成；组件再拆为“子组件”。
     * 所有下标均按 HL7 习惯从 1 起始。
     */
    public static final class Field {
        private final List<Repetition> repetitions;

        public Field(List<Repetition> repetitions) {
            this.repetitions = List.copyOf(repetitions);
        }

        public int repetitionCount() {
            return repetitions.size();
        }

        public String value() {
            return value(1, 1, 1);
        }

        public String value(int component) {
            return value(1, component, 1);
        }

        public String value(int repetition, int component) {
            return value(repetition, component, 1);
        }

        public String value(int repetition, int component, int subcomponent) {
            if (repetition < 1 || repetition > repetitions.size()) {
                return null;
            }
            return repetitions.get(repetition - 1).value(component, subcomponent);
        }

        public List<String> repetitionValues() {
            return repetitions.stream().map(r -> r.value(1, 1)).toList();
        }
    }

    public static final class Repetition {
        private final List<Component> components;

        public Repetition(List<Component> components) {
            this.components = List.copyOf(components);
        }

        public String value(int component, int subcomponent) {
            if (component < 1 || component > components.size()) {
                return null;
            }
            return components.get(component - 1).value(subcomponent);
        }
    }

    public static final class Component {
        private final List<String> subcomponents;

        public Component(List<String> subcomponents) {
            this.subcomponents = List.copyOf(subcomponents);
        }

        public String value(int subcomponent) {
            if (subcomponent < 1 || subcomponent > subcomponents.size()) {
                return null;
            }
            return subcomponents.get(subcomponent - 1);
        }
    }
}
