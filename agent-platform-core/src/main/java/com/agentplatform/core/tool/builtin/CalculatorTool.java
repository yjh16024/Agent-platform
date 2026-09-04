package com.agentplatform.core.tool.builtin;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 内置计算器工具。
 * <p>
 * 自研安全表达式求值器（递归下降），支持 + - * / % ^、括号与
 * sqrt/sin/cos/abs 函数。避免 Nashorn（JDK 15+ 已移除）与任意代码执行风险。
 * </p>
 */
@Component("calc")
public class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calc";
    }

    @Override
    public String description() {
        return "Evaluate a mathematical expression, e.g. '2+3*4', 'sqrt(16)', 'sin(0)'";
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String expression = args.path("expression").asText();
        if (expression == null || expression.isBlank()) {
            return ToolResult.fail("Missing required argument 'expression'");
        }
        try {
            double result = new SafeEvaluator(expression).evaluate();
            return ToolResult.ok(format(result));
        } catch (Exception e) {
            return ToolResult.fail("Invalid expression: " + e.getMessage());
        }
    }

    private String format(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 1e8) / 1e8);
    }

    /**
     * 简单递归下降表达式求值器（仅数字与有限运算符，杜绝代码注入）。
     */
    static final class SafeEvaluator {
        private final String s;
        private int pos = 0;

        SafeEvaluator(String s) {
            this.s = s == null ? "" : s;
        }

        double evaluate() {
            double v = parseExpression();
            skipWs();
            if (pos < s.length()) {
                throw new IllegalArgumentException("Unexpected token at position " + pos);
            }
            return v;
        }

        private double parseExpression() {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (peek('+')) {
                    pos++;
                    v += parseTerm();
                } else if (peek('-')) {
                    pos++;
                    v -= parseTerm();
                } else {
                    return v;
                }
            }
        }

        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipWs();
                if (peek('*')) {
                    pos++;
                    v *= parseFactor();
                } else if (peek('/')) {
                    pos++;
                    v /= parseFactor();
                } else if (peek('%')) {
                    pos++;
                    v %= parseFactor();
                } else {
                    return v;
                }
            }
        }

        private double parseFactor() {
            skipWs();
            if (peek('+')) {
                pos++;
                return parseFactor();
            }
            if (peek('-')) {
                pos++;
                return -parseFactor();
            }
            if (peek('(')) {
                pos++;
                double v = parseExpression();
                skipWs();
                expect(')');
                return v;
            }
            if (Character.isLetter(s.charAt(pos))) {
                return parseFunction();
            }
            return parseNumber();
        }

        private double parseFunction() {
            String name = readIdentifier();
            skipWs();
            expect('(');
            double arg = parseExpression();
            expect(')');
            return switch (name) {
                case "sqrt" -> Math.sqrt(arg);
                case "sin" -> Math.sin(arg);
                case "cos" -> Math.cos(arg);
                case "abs" -> Math.abs(arg);
                case "log" -> Math.log(arg);
                default -> throw new IllegalArgumentException("Unknown function: " + name);
            };
        }

        private double parseNumber() {
            skipWs();
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Expect number at position " + pos);
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private String readIdentifier() {
            int start = pos;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            skipWs();
            if (!peek(c)) {
                throw new IllegalArgumentException("Expect '" + c + "' at position " + pos);
            }
            pos++;
        }
    }
}