// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/** Closed nested predicate values without changing the historical mutation scalar reader. */
final class PredicatePropertyValue {

    private PredicatePropertyValue() {
    }

    /**
     * Tests the closed property envelope and its nested scalar spellings.
     * @param written the equality operand as the caller wrote it
     * @param contract the authenticated scalar bounds
     * @return whether the nested documents have canonical types and members
     */
    static boolean usable(DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Mapping mapping)
                || mapping.members().keySet().stream().anyMatch(member ->
                        !List.of(PropertyValue.CARDINALITY, PropertyValue.VALUE, PropertyValue.VALUES)
                                .contains(member))) {
            return false;
        }
        final DocumentValue values = mapping.member(PropertyValue.VALUES)
                .orElse(new DocumentValue.Nothing());
        if (values instanceof final DocumentValue.Sequence sequence) {
            return sequence.items().stream().allMatch(value -> scalar(value, contract));
        }
        return scalar(mapping.member(PropertyValue.VALUE).orElse(new DocumentValue.Nothing()), contract);
    }

    private static boolean scalar(DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Mapping mapping)
                || !mapping.members().keySet().equals(java.util.Set.of(PropertyScalar.TYPE,
                        PropertyScalar.VALUE))) {
            return false;
        }
        final PropertyScalar.Outcome parsed = PropertyScalar.of(written);
        if (!(parsed instanceof final PropertyScalar.Held held)) {
            return false;
        }
        final DocumentValue value = mapping.member(PropertyScalar.VALUE).orElseThrow();
        if (held.scalar().kind() == ScalarKind.BOOLEAN) {
            return value instanceof DocumentValue.Flag;
        }
        return value instanceof DocumentValue.Text && spelling(held.scalar(), contract);
    }

    private static boolean spelling(PropertyScalar scalar, AgentContract contract) {
        final String text = scalar.value();
        try {
            return switch (scalar.kind()) {
                case INTEGER -> Long.toString(Long.parseLong(text)).equals(text);
                case DECIMAL -> decimal(text, contract);
                case DATE_TIME -> text.matches("[0-9]{4}-.*Z") && !text.startsWith("0000")
                        && Instant.parse(text).toString().equals(text);
                case REPOSITORY_PATH -> PredicatePropertyPath.absolute(text, contract);
                default -> text.getBytes(StandardCharsets.UTF_8).length
                        <= contract.value(ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES);
            };
        } catch (final NumberFormatException | DateTimeParseException invalid) {
            return false;
        }
    }

    private static boolean decimal(String text, AgentContract contract) {
        if (text.length() > contract.value(ContractLimit.MAXIMUM_DECIMAL_BYTES)) {
            return false;
        }
        final String unsigned = text.startsWith("-") ? text.substring(1) : text;
        return unsignedDecimal(unsigned, contract)
                && (!text.startsWith("-") || new BigDecimal(text).signum() != 0);
    }

    private static boolean unsignedDecimal(String text, AgentContract contract) {
        final int point = text.indexOf('.');
        final String integer = point < 0 ? text : text.substring(0, point);
        final String fraction = point < 0 ? "" : text.substring(point + 1);
        return integerDigits(integer) && (point < 0 || digits(fraction))
                && integer.length() <= contract.value(ContractLimit.MAXIMUM_DECIMAL_INTEGER_DIGITS)
                && fraction.length() <= contract.value(ContractLimit.MAXIMUM_DECIMAL_FRACTION_DIGITS);
    }

    private static boolean integerDigits(String value) {
        return "0".equals(value) || !value.startsWith("0") && digits(value);
    }

    private static boolean digits(String value) {
        return !value.isEmpty() && value.chars().allMatch(character -> character >= '0' && character <= '9');
    }
}
