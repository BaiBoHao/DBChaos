package chaos.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser used by the registry loader.
 * It supports the JSON types needed by registry.json without adding third-party dependencies.
 */
public final class SimpleJsonParser {
    private final String text;
    private int index;

    private SimpleJsonParser(String text) {
        this.text = text == null ? "" : text;
    }

    public static Object parse(String text) {
        SimpleJsonParser parser = new SimpleJsonParser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw parser.error("存在多余内容");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (isEnd()) {
            throw error("JSON 内容为空");
        }

        char ch = current();
        if (ch == '{') {
            return parseObject();
        }
        if (ch == '[') {
            return parseArray();
        }
        if (ch == '"') {
            return parseString();
        }
        if (ch == 't') {
            return parseLiteral("true", Boolean.TRUE);
        }
        if (ch == 'f') {
            return parseLiteral("false", Boolean.FALSE);
        }
        if (ch == 'n') {
            return parseLiteral("null", null);
        }
        if (ch == '-' || Character.isDigit(ch)) {
            return parseNumber();
        }
        throw error("无法识别的 JSON 标记: " + ch);
    }

    private Map<String, Object> parseObject() {
        expect('{');
        LinkedHashMap<String, Object> object = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (peek('}')) {
            index++;
            return object;
        }

        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw error("对象键必须是字符串");
            }
            String key = parseString();
            skipWhitespace();
            expect(':');
            object.put(key, parseValue());
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            expect(',');
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> array = new ArrayList<Object>();
        skipWhitespace();
        if (peek(']')) {
            index++;
            return array;
        }

        while (true) {
            array.add(parseValue());
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            expect(',');
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (!isEnd()) {
            char ch = text.charAt(index++);
            if (ch == '"') {
                return builder.toString();
            }
            if (ch == '\\') {
                if (isEnd()) {
                    throw error("字符串转义不完整");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escaped);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        builder.append(parseUnicodeEscape());
                        break;
                    default:
                        throw error("不支持的转义字符: \\" + escaped);
                }
                continue;
            }
            if (ch < 0x20) {
                throw error("字符串中包含非法控制字符");
            }
            builder.append(ch);
        }
        throw error("字符串缺少结束引号");
    }

    private char parseUnicodeEscape() {
        if (index + 4 > text.length()) {
            throw error("Unicode 转义长度不足");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char ch = text.charAt(index++);
            int digit = Character.digit(ch, 16);
            if (digit < 0) {
                throw error("非法的 Unicode 转义字符: " + ch);
            }
            value = (value << 4) + digit;
        }
        return (char) value;
    }

    private Object parseLiteral(String literal, Object value) {
        if (!text.regionMatches(index, literal, 0, literal.length())) {
            throw error("无法识别的字面量");
        }
        index += literal.length();
        return value;
    }

    private Number parseNumber() {
        int start = index;
        if (peek('-')) {
            index++;
        }
        if (isEnd()) {
            throw error("数字格式不完整");
        }
        if (peek('0')) {
            index++;
        } else {
            readDigits();
        }

        boolean decimal = false;
        if (!isEnd() && peek('.')) {
            decimal = true;
            index++;
            readDigits();
        }

        if (!isEnd() && (peek('e') || peek('E'))) {
            decimal = true;
            index++;
            if (!isEnd() && (peek('+') || peek('-'))) {
                index++;
            }
            readDigits();
        }

        String raw = text.substring(start, index);
        try {
            if (decimal) {
                return Double.valueOf(raw);
            }
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw error("数字格式非法: " + raw);
        }
    }

    private void readDigits() {
        if (isEnd() || !Character.isDigit(current())) {
            throw error("数字格式非法");
        }
        while (!isEnd() && Character.isDigit(current())) {
            index++;
        }
    }

    private void expect(char expected) {
        skipWhitespace();
        if (isEnd() || text.charAt(index) != expected) {
            throw error("期望字符 '" + expected + "'");
        }
        index++;
    }

    private boolean peek(char expected) {
        return !isEnd() && text.charAt(index) == expected;
    }

    private char current() {
        return text.charAt(index);
    }

    private void skipWhitespace() {
        while (!isEnd()) {
            char ch = text.charAt(index);
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                index++;
                continue;
            }
            break;
        }
    }

    private boolean isEnd() {
        return index >= text.length();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " (position=" + index + ")");
    }
}
