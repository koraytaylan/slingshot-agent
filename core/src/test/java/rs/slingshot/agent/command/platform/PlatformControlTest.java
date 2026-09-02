// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The gate every platform control passes through, and the vocabulary those commands share.
 *
 * <p>What is proved here is mostly refusal. A deployment that does not keep a change must not be
 * asked to make one — not because the request would fail, but because it would succeed, be reported
 * as done, and be gone by the next release, with the operator already three steps past believing
 * it.</p>
 */
final class PlatformControlTest {

    private static final String CLOUD = "aem-cloud-service";

    @Test
    @DisplayName("a control the deployment does not provide is refused, naming the deployment")
    void acontrolTheDeploymentLacksIsRefused() {
        final PlatformControl immutable = PlatformControl.of(CLOUD,
                Set.of(ControlCapability.WORKFLOW_CONTROL, ControlCapability.JOB_CONTROL));
        final PlatformControl.Refused refused = assertInstanceOf(PlatformControl.Refused.class,
                immutable.permits(ControlCapability.CONFIGURATION_CHANGE),
                "a configuration change was permitted on a deployment that does not keep one");
        assertEquals(PlatformControl.NOT_PERMITTED, refused.category());
        assertTrue(refused.detail().contains(CLOUD),
                "the refusal does not say which deployment refused, so the operator learns no"
                        + " rather than where: " + refused.detail());
        assertInstanceOf(PlatformControl.Permitted.class,
                immutable.permits(ControlCapability.WORKFLOW_CONTROL),
                "a control the deployment does provide was refused");
    }

    @Test
    @DisplayName("the gate answers the same for everybody, because it is not about who is asking")
    void thegateIsNotAboutWhoIsAsking() {
        final PlatformControl immutable = PlatformControl.of(CLOUD, Set.of());
        assertEquals(immutable.permits(ControlCapability.BUNDLE_LIFECYCLE),
                immutable.permits(ControlCapability.BUNDLE_LIFECYCLE),
                "the same question answered twice gave two answers, and this gate has no input"
                        + " beyond the deployment and the capability — an administrator is refused"
                        + " exactly as firmly as everybody else");
        assertEquals(CLOUD, immutable.deployment());
        assertEquals(Set.of(), immutable.provided());
    }

    @Test
    @DisplayName("every capability has a spelling and every spelling names one capability")
    void thecapabilitiesRoundTrip() {
        assertEquals(ControlCapability.spellings().size(),
                Set.copyOf(ControlCapability.spellings()).size(),
                "two capabilities are spelled the same way");
        ControlCapability.spellings().forEach(spelled ->
                assertTrue(ControlCapability.named(spelled).isPresent(),
                        spelled + " is a spelling nothing names"));
        assertEquals(Optional.empty(), ControlCapability.named("time_travel"));
        assertEquals(ControlCapability.spellings().size(),
                PlatformControl.everyCapability().size());
    }

    @Test
    @DisplayName("a value the platform calls a secret is withheld, and so is one it says nothing about")
    void onlyDescribedNonSecretsAreReported() {
        assertInstanceOf(ValueDisclosure.Redacted.class,
                ValueDisclosure.of(ValueDisclosure.Evidence.PASSWORD, scalar("hunter2")),
                "a value the platform calls a password was reported");
        assertInstanceOf(ValueDisclosure.Redacted.class,
                ValueDisclosure.of(ValueDisclosure.Evidence.UNAVAILABLE, scalar("hunter2")),
                "a value nothing describes was reported, and nobody-told-us is not it-is-safe —"
                        + " an undescribed property is the most likely one to be a credential"
                        + " somebody added by hand");
        assertInstanceOf(ValueDisclosure.Visible.class,
                ValueDisclosure.of(ValueDisclosure.Evidence.NON_PASSWORD, scalar("8080")),
                "a value the platform describes and does not call a secret was withheld");
        assertTrue(ValueDisclosure.Evidence.NON_PASSWORD.permitsReading(),
                "the one evidence that permits reading no longer does");
        assertTrue(!ValueDisclosure.Evidence.PASSWORD.permitsReading()
                        && !ValueDisclosure.Evidence.UNAVAILABLE.permitsReading(),
                "more than one of the three kinds of evidence permits reading a value");
    }

    @Test
    @DisplayName("a withheld property carries no value member at all, rather than a masked one")
    void awithheldPropertyCarriesNoValueMember() {
        final DocumentValue.Mapping withheld = ValueDisclosure.documentOf(
                ValueDisclosure.Evidence.PASSWORD, new ValueDisclosure.Redacted());
        final DocumentValue observation = withheld.member(ValueDisclosure.OBSERVATION).orElseThrow();
        assertEquals(List.of(ValueDisclosure.VISIBILITY),
                List.copyOf(assertInstanceOf(DocumentValue.Mapping.class, observation)
                        .members().keySet()),
                "a withheld value carries something in the value member's place, and anything that"
                        + " occupies it is something a reader can measure: a mask has a length, it"
                        + " changes when the secret changes, and two answers compared tell you so");
        assertTrue(!String.valueOf(withheld).contains("hunter2")
                        && !String.valueOf(withheld).contains("*"),
                "the withheld answer carries the secret or a mask of it: " + withheld);
        assertEquals(new DocumentValue.Text(ValueDisclosure.PASSWORD_EVIDENCE),
                withheld.member(ValueDisclosure.METATYPE_EVIDENCE).orElseThrow(),
                "the answer does not say why the value is missing, so a caller cannot tell a"
                        + " withheld property from a broken one");
    }

    @Test
    @DisplayName("a reported property carries its type and its cardinality, not just its text")
    void areportedPropertyCarriesItsType() {
        final DocumentValue.Mapping reported = ValueDisclosure.documentOf(
                ValueDisclosure.Evidence.NON_PASSWORD,
                new ValueDisclosure.Visible(scalar("8080")));
        final DocumentValue.Mapping observation = assertInstanceOf(DocumentValue.Mapping.class,
                reported.member(ValueDisclosure.OBSERVATION).orElseThrow());
        final DocumentValue.Mapping value = assertInstanceOf(DocumentValue.Mapping.class,
                observation.member(ValueDisclosure.VALUE).orElseThrow());
        assertEquals(new DocumentValue.Text("integer"),
                value.member(ConfigurationValue.TYPE).orElseThrow(),
                "the value does not carry its type, and 8080 written back as text is a"
                        + " configuration that no longer starts a listener");
        assertEquals(new DocumentValue.Text("8080"),
                value.member(ConfigurationValue.VALUE).orElseThrow());
        assertTrue(value.member(ConfigurationValue.VALUES).isEmpty(),
                "a single value was also carried as a list, and a scalar and an array of one are"
                        + " different configurations to the service reading them");
    }

    @Test
    @DisplayName("several values are carried as a list and one is not, which is not a convenience")
    void cardinalityDecidesWhichMemberCarriesTheValue() {
        final DocumentValue.Mapping several = new ConfigurationValue("string",
                ConfigurationValue.Cardinality.COLLECTION, List.of("a", "b")).document();
        assertEquals(new DocumentValue.Sequence(
                        List.of(new DocumentValue.Text("a"), new DocumentValue.Text("b"))),
                several.member(ConfigurationValue.VALUES).orElseThrow());
        assertTrue(several.member(ConfigurationValue.VALUE).isEmpty(),
                "several values were also carried in the single-value member");
        assertTrue(ConfigurationValue.Cardinality.SCALAR.isSingle()
                        && !ConfigurationValue.Cardinality.COLLECTION.isSingle(),
                "the cardinality no longer decides how many values there are");
        assertEquals(Optional.empty(), ConfigurationValue.Cardinality.named("several"));
    }

    @Test
    @DisplayName("both platform states are two-valued, named, and read from what the client sends")
    void thestatesAreNamedRatherThanTwoValued() {
        assertEquals(2, SuspensionState.values().length,
                "the suspension state gained a value, and what a caller may ask for is exactly two"
                        + " things");
        assertEquals(2, AccountState.values().length);
        assertEquals(Optional.of(SuspensionState.SUSPENDED),
                SuspensionState.of(new DocumentValue.Text("suspended")));
        assertEquals(Optional.empty(), SuspensionState.of(new DocumentValue.Text("paused")));
        assertEquals(Optional.of(AccountState.DISABLED),
                AccountState.of(new DocumentValue.Flag(DocumentValue.Truth.TRUE)),
                "the client carries this as a flag and this side reads it into a name, because"
                        + " disabled(false) is a double negative in the one place a mistake locks"
                        + " somebody out of their own instance");
        assertEquals(Optional.of(AccountState.ENABLED),
                AccountState.of(new DocumentValue.Flag(DocumentValue.Truth.FALSE)));
        assertEquals(Optional.empty(), AccountState.of(new DocumentValue.Text("disabled")));
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                AccountState.DISABLED.flag());
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                AccountState.ENABLED.flag());
        assertEquals(List.of("enabled", "disabled"), AccountState.spellings());
        assertEquals(Optional.of(AccountState.ENABLED), AccountState.named("enabled"));
        assertEquals(Optional.empty(), AccountState.named("locked"));
        assertEquals("suspended", SuspensionState.SUSPENDED.spelling());
        assertEquals(List.of("suspended", "running"), SuspensionState.spellings());
    }

    @Test
    @DisplayName("every evidence has a spelling and every spelling names one kind of evidence")
    void theevidenceRoundTrips() {
        assertEquals(ValueDisclosure.Evidence.values().length,
                ValueDisclosure.Evidence.spellings().size());
        ValueDisclosure.Evidence.spellings().forEach(spelled ->
                assertTrue(ValueDisclosure.Evidence.named(spelled).isPresent(),
                        spelled + " is a spelling nothing names"));
        assertEquals(Optional.of(ValueDisclosure.Evidence.PASSWORD),
                ValueDisclosure.Evidence.named(ValueDisclosure.PASSWORD_EVIDENCE));
        assertEquals(Optional.empty(), ValueDisclosure.Evidence.named("probably_fine"),
                "a spelling nobody publishes was read as evidence, and the one that matters here"
                        + " would be read as permission to report a credential");
        assertEquals(List.of("string", "boolean", "character", "byte", "short", "integer", "long",
                        "float", "double"), ConfigurationValue.TYPES,
                "the closed set of configuration types no longer matches the client's own");
        assertEquals(ConfigurationValue.Cardinality.values().length,
                ConfigurationValue.Cardinality.spellings().size());
    }

    @Test
    @DisplayName("what the platform reports about a workflow is wider than what anybody may ask for")
    void theobservedStateIsWiderThanTheRequestedOne() {
        assertTrue(WorkflowInstanceState.values().length > SuspensionState.values().length,
                "the observed state and the requested one now hold the same number of values, and"
                        + " an instance that finished while the request was in flight is none of"
                        + " the things anybody can ask for");
        SuspensionState.spellings().forEach(spelled ->
                assertTrue(WorkflowInstanceState.named(spelled).isPresent(),
                        spelled + " is a state a caller may ask for and the platform cannot"
                                + " report, so the request could never be confirmed"));
        assertTrue(WorkflowInstanceState.SUSPENDED.agreesWith(SuspensionState.SUSPENDED),
                "asking for suspended and observing suspended is not agreement");
        assertTrue(!WorkflowInstanceState.COMPLETED.agreesWith(SuspensionState.SUSPENDED),
                "an instance that finished was reported as agreeing with a request to suspend it");
        assertEquals(Optional.empty(), WorkflowInstanceState.named("paused"));
    }

    private static ConfigurationValue scalar(String value) {
        return new ConfigurationValue("8080".equals(value) ? "integer" : "string",
                ConfigurationValue.Cardinality.SCALAR, List.of(value));
    }
}
