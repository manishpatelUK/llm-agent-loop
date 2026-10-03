package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates arithmetic exactly, in decimal — the point being that {@code 0.1 + 0.2} is
 * {@code 0.3} and money sums come out to the penny, which a model doing arithmetic in its head
 * (or a {@code double}) doesn't guarantee.
 *
 * <p>Grammar: {@code + - * / %} (remainder), {@code ^} (power, right-associative), unary minus,
 * parentheses, the constants {@code pi} and {@code e}, and the functions {@code abs},
 * {@code round(x[, places])} (half-up), {@code floor}, {@code ceil}, {@code sqrt}, {@code pow},
 * {@code ln}, {@code log10}, {@code exp}, {@code min}, {@code max}, {@code sum}, {@code avg}.
 * Work is done to 34 significant digits ({@link MathContext#DECIMAL128}) and shown to 25; functions that are
 * inherently irrational ({@code sqrt}, {@code ln}, non-integer powers...) are computed in
 * {@code double} precision.
 */
final class Calculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final MathContext DISPLAY = new MathContext(25, RoundingMode.HALF_EVEN);
    private static final int MAX_INTEGER_POWER = 10_000;
    private static final int MAX_EXPRESSION_LENGTH = 2_000;

    private final String input;
    private int pos;

    private Calculator(String input) {
        this.input = input;
    }

    static BigDecimal evaluate(String expression) {
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new ToolInputException("Expression is longer than " + MAX_EXPRESSION_LENGTH + " characters");
        }
        Calculator calculator = new Calculator(expression);
        BigDecimal result = calculator.expression();
        calculator.skipWhitespace();
        if (calculator.pos < expression.length()) {
            throw calculator.error("Unexpected '" + expression.charAt(calculator.pos) + "'");
        }
        return result;
    }

    /**
     * {@code result} rounded to {@link #DISPLAY} significant digits, without trailing zeros or
     * exponent notation — e.g. {@code 1200}, not {@code 1.2E+3}. Working in 34 digits but showing 25
     * keeps guard digits, so {@code 1 / 3 * 3} shows as {@code 1}, not {@code 0.999...}.
     */
    static String format(BigDecimal result) {
        BigDecimal stripped = result.round(DISPLAY).stripTrailingZeros();
        return Math.abs(stripped.scale()) > 100 ? stripped.toString() : stripped.toPlainString();
    }

    private BigDecimal expression() {
        BigDecimal value = term();
        while (true) {
            if (accept('+')) {
                value = value.add(term(), MC);
            } else if (accept('-')) {
                value = value.subtract(term(), MC);
            } else {
                return value;
            }
        }
    }

    private BigDecimal term() {
        BigDecimal value = power();
        while (true) {
            if (accept('*')) {
                value = value.multiply(power(), MC);
            } else if (accept('/')) {
                value = divide(value, power());
            } else if (accept('%')) {
                BigDecimal divisor = power();
                requireNonZero(divisor);
                value = value.remainder(divisor, MC);
            } else {
                return value;
            }
        }
    }

    private BigDecimal power() {
        BigDecimal base = unary();
        if (accept('^')) {
            return pow(base, power());
        }
        return base;
    }

    private BigDecimal unary() {
        if (accept('-')) {
            return unary().negate(MC);
        }
        if (accept('+')) {
            return unary();
        }
        return primary();
    }

    private BigDecimal primary() {
        skipWhitespace();
        if (accept('(')) {
            BigDecimal value = expression();
            expect(')');
            return value;
        }
        if (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            return number();
        }
        if (pos < input.length() && Character.isLetter(input.charAt(pos))) {
            String name = identifier();
            if (accept('(')) {
                List<BigDecimal> args = new ArrayList<>();
                if (!accept(')')) {
                    do {
                        args.add(expression());
                    } while (accept(','));
                    expect(')');
                }
                return function(name, args);
            }
            return switch (name) {
                case "pi" -> new BigDecimal(Math.PI, MC);
                case "e" -> new BigDecimal(Math.E, MC);
                default -> throw error("Unknown name '" + name + "'");
            };
        }
        throw error(pos < input.length() ? "Unexpected '" + input.charAt(pos) + "'" : "Expression ended unexpectedly");
    }

    private BigDecimal function(String name, List<BigDecimal> args) {
        return switch (name) {
            case "abs" -> one(name, args).abs(MC);
            case "floor" -> one(name, args).setScale(0, RoundingMode.FLOOR);
            case "ceil" -> one(name, args).setScale(0, RoundingMode.CEILING);
            case "round" -> {
                if (args.isEmpty() || args.size() > 2) {
                    throw error("round takes 1 or 2 arguments");
                }
                int places = args.size() == 2 ? integer(args.get(1), "round's places") : 0;
                yield args.get(0).setScale(places, RoundingMode.HALF_UP);
            }
            case "sqrt" -> {
                BigDecimal x = one(name, args);
                if (x.signum() < 0) {
                    throw error("sqrt of a negative number");
                }
                yield x.sqrt(MC);
            }
            case "pow" -> {
                if (args.size() != 2) {
                    throw error("pow takes 2 arguments");
                }
                yield pow(args.get(0), args.get(1));
            }
            case "ln" -> viaDouble(Math.log(positive(one(name, args), name).doubleValue()));
            case "log10" -> viaDouble(Math.log10(positive(one(name, args), name).doubleValue()));
            case "exp" -> viaDouble(Math.exp(one(name, args).doubleValue()));
            case "min" -> atLeastOne(name, args).stream().reduce(BigDecimal::min).orElseThrow();
            case "max" -> atLeastOne(name, args).stream().reduce(BigDecimal::max).orElseThrow();
            case "sum" -> atLeastOne(name, args).stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
            case "avg" -> divide(atLeastOne(name, args).stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC)),
                    BigDecimal.valueOf(args.size()));
            default -> throw error("Unknown function '" + name + "'");
        };
    }

    private BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        boolean integral = exponent.stripTrailingZeros().scale() <= 0;
        if (integral && exponent.abs().compareTo(BigDecimal.valueOf(MAX_INTEGER_POWER)) <= 0) {
            int n = exponent.intValueExact();
            if (n >= 0) {
                return base.pow(n, MC);
            }
            requireNonZero(base);
            return BigDecimal.ONE.divide(base.pow(-n, MC), MC);
        }
        if (base.signum() < 0) {
            throw error("A negative number can't be raised to a non-integer power");
        }
        return viaDouble(Math.pow(base.doubleValue(), exponent.doubleValue()));
    }

    private BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        requireNonZero(divisor);
        return dividend.divide(divisor, MC);
    }

    private void requireNonZero(BigDecimal divisor) {
        if (divisor.signum() == 0) {
            throw error("Division by zero");
        }
    }

    private BigDecimal viaDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw error("Result is not a finite number");
        }
        return new BigDecimal(value, MC);
    }

    private BigDecimal positive(BigDecimal x, String name) {
        if (x.signum() <= 0) {
            throw error(name + " needs a positive number");
        }
        return x;
    }

    private BigDecimal one(String name, List<BigDecimal> args) {
        if (args.size() != 1) {
            throw error(name + " takes 1 argument");
        }
        return args.get(0);
    }

    private List<BigDecimal> atLeastOne(String name, List<BigDecimal> args) {
        if (args.isEmpty()) {
            throw error(name + " needs at least 1 argument");
        }
        return args;
    }

    private int integer(BigDecimal value, String what) {
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw error(what + " must be a whole number");
        }
    }

    private BigDecimal number() {
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')
                && pos + 1 < input.length()
                && (Character.isDigit(input.charAt(pos + 1)) || input.charAt(pos + 1) == '-' || input.charAt(pos + 1) == '+')) {
            pos += 2;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        try {
            return new BigDecimal(input.substring(start, pos), MC);
        } catch (NumberFormatException e) {
            throw error("Malformed number '" + input.substring(start, pos) + "'");
        }
    }

    private String identifier() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
            pos++;
        }
        return input.substring(start, pos).toLowerCase(Locale.ROOT);
    }

    private boolean accept(char c) {
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!accept(c)) {
            throw error("Expected '" + c + "'");
        }
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private ToolInputException error(String message) {
        return new ToolInputException(message + " at position " + (pos + 1) + " in: " + input);
    }
}
