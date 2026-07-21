// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Compact, flat JSON for one machine-record line: an object of string keys to string, integer, boolean, or null values.
 * Deliberately not a general JSON library, only the shape the report emits and reads back, so the Java-8 core carries
 * the durable readback contract with no third-party dependency. Numbers render via the JDK integer types' own
 * {@code toString}, which is locale-independent, so on-disk values are stable on any machine.
 */
final class Json {
    private Json() {}

    /** Serialize {@code fields} to one compact JSON object line, keys in iteration order. */
    static String writeObject(Map<String, @Nullable Object> fields) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, @Nullable Object> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, entry.getKey());
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
        return out.toString();
    }

    /**
     * Parse one flat JSON object line into a {@link LinkedHashMap} (insertion-ordered) of string keys to String / Long
     * / Double / Boolean / null. Throws {@link IllegalArgumentException} on anything that is not a single well-formed
     * flat object, so a torn or corrupt line is rejected rather than half-read.
     */
    static Map<String, @Nullable Object> parseObject(String line) {
        Cursor cursor = new Cursor(line);
        cursor.skipWhitespace();
        cursor.expect('{');
        Map<String, @Nullable Object> result = new LinkedHashMap<>();
        cursor.skipWhitespace();
        if (cursor.peek() == '}') {
            cursor.next();
            cursor.skipWhitespace();
            cursor.expectEnd();
            return result;
        }
        while (true) {
            cursor.skipWhitespace();
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            cursor.skipWhitespace();
            result.put(key, cursor.readValue());
            cursor.skipWhitespace();
            char delimiter = cursor.next();
            if (delimiter == '}') {
                break;
            }
            if (delimiter != ',') {
                throw malformed(line);
            }
        }
        cursor.skipWhitespace();
        cursor.expectEnd();
        return result;
    }

    private static void writeValue(StringBuilder out, @Nullable Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long) {
            out.append(value.toString());
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else {
            throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (character < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
            }
        }
        out.append('"');
    }

    private static IllegalArgumentException malformed(String line) {
        return new IllegalArgumentException("malformed JSON object line: " + line);
    }

    /** A scanning index over one line; every read advances past what it consumed and validates as it goes. */
    private static final class Cursor {
        private final String source;
        private int index;

        Cursor(String source) {
            this.source = source;
        }

        void skipWhitespace() {
            while (index < source.length()) {
                char character = source.charAt(index);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (index >= source.length()) {
                throw malformed(source);
            }
            return source.charAt(index);
        }

        char next() {
            if (index >= source.length()) {
                throw malformed(source);
            }
            return source.charAt(index++);
        }

        void expect(char expected) {
            if (next() != expected) {
                throw malformed(source);
            }
        }

        void expectEnd() {
            if (index != source.length()) {
                throw malformed(source);
            }
        }

        String readString() {
            if (next() != '"') {
                throw malformed(source);
            }
            StringBuilder out = new StringBuilder();
            while (true) {
                char character = next();
                if (character == '"') {
                    return out.toString();
                }
                if (character == '\\') {
                    out.append(readEscape());
                } else if (character < 0x20) {
                    throw malformed(source); // a raw control char is not legal inside a JSON string
                } else {
                    out.append(character);
                }
            }
        }

        char readEscape() {
            char escape = next();
            switch (escape) {
                case '"':
                    return '"';
                case '\\':
                    return '\\';
                case '/':
                    return '/';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'u':
                    return readUnicode();
                default:
                    throw malformed(source);
            }
        }

        char readUnicode() {
            if (index + 4 > source.length()) {
                throw malformed(source);
            }
            String hex = source.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw malformed(source);
            }
        }

        @Nullable
        Object readValue() {
            char character = peek();
            if (character == '"') {
                return readString();
            }
            if (character == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            }
            if (character == 'f') {
                expectLiteral("false");
                return Boolean.FALSE;
            }
            if (character == 'n') {
                expectLiteral("null");
                return null;
            }
            if (character == '-' || (character >= '0' && character <= '9')) {
                return readNumber();
            }
            throw malformed(source);
        }

        void expectLiteral(String literal) {
            if (!source.regionMatches(index, literal, 0, literal.length())) {
                throw malformed(source);
            }
            index += literal.length();
        }

        Object readNumber() {
            int start = index;
            boolean floating = false;
            if (peek() == '-') {
                index++;
            }
            while (index < source.length()) {
                char character = source.charAt(index);
                if (character >= '0' && character <= '9') {
                    index++;
                } else if (isFloatingPunctuation(character)) {
                    floating = true;
                    index++;
                } else {
                    break;
                }
            }
            String token = source.substring(start, index);
            try {
                return floating ? (Object) Double.parseDouble(token) : (Object) Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw malformed(source);
            }
        }

        private static boolean isFloatingPunctuation(char character) {
            return character == '.' || character == 'e' || character == 'E'
                    || character == '+' || character == '-';
        }
    }
}
