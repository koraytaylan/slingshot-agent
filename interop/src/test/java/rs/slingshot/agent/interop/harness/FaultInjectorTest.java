// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the three injectors declare, checked without starting anything.
 *
 * <p>Each of them is mostly a set of enumerated points and a claim about what each one produces,
 * and both are worth checking before a container is started: an injector whose faults collapse to
 * one answer is a suite that cannot tell two recoveries apart, and one whose points are not the
 * cross product is a suite that runs the injections somebody thought of.</p>
 */
final class FaultInjectorTest {

    /** Four faults at seven points, which is what the repository suite runs. */
    private static final int EVERY_REPOSITORY_INJECTION = 28;

    /** Three faults on five services. */
    private static final int EVERY_PLATFORM_INJECTION = 15;

    @Test
    @DisplayName("the four repository faults stay four answers, with none standing in for another")
    void thefourRepositoryFaultsAreFourAnswers() {
        assertTrue(RepositoryFaultInjector.dispositionsAreDistinct(),
                "two faults became one answer, which means the recovery written for one of them is"
                        + " running for the other");
        assertEquals(EVERY_REPOSITORY_INJECTION,
                RepositoryFaultInjector.everyInjection().size(),
                "the injections are no longer every fault at every point, and the pair somebody"
                        + " would leave out is the one nobody has thought about");
        assertEquals(EVERY_REPOSITORY_INJECTION, RepositoryFaultInjector.everyInjection().stream()
                        .map(RepositoryFaultInjector.Injection::spelling).distinct().count(),
                "two injections are spelled the same way, so a finding would not say which");
    }

    @Test
    @DisplayName("every repository fault and point answers to the name it is written down under")
    void everyrepositoryNameIsFound() {
        Arrays.stream(RepositoryFaultInjector.Fault.values()).forEach(fault ->
                assertEquals(Optional.of(fault),
                        RepositoryFaultInjector.Fault.named(fault.spelling())));
        Arrays.stream(RepositoryFaultInjector.Point.values()).forEach(point ->
                assertEquals(Optional.of(point),
                        RepositoryFaultInjector.Point.named(point.spelling())));
        assertEquals(Optional.empty(), RepositoryFaultInjector.Fault.named("nothing-like-this"));
        assertEquals(Optional.empty(), RepositoryFaultInjector.Point.named("nothing-like-this"));
    }

    @Test
    @DisplayName("a platform that says nothing is the only fault nobody can answer for")
    void anonAnsweringPlatformIsTheUnknownOne() {
        assertTrue(PlatformFaultInjector.unknownOutcomeIsReachable(),
                "no fault produces the unknown outcome, which makes it a category nobody can reach"
                        + " and therefore a sentence in a document");
        assertTrue(PlatformFaultInjector.aRejectionAndAThrowLookAlike(),
                "a caller can tell a platform that refused from one that failed, which couples"
                        + " them to this product's opinion of somebody else's exception");
        assertEquals(EVERY_PLATFORM_INJECTION, PlatformFaultInjector.everyInjection().size(),
                "a service was left out, and a service left out is one whose failure nobody has"
                        + " seen");
        Arrays.stream(PlatformFaultInjector.Service.values()).forEach(service ->
                assertEquals(Optional.of(service),
                        PlatformFaultInjector.Service.named(service.spelling())));
        Arrays.stream(PlatformFaultInjector.Fault.values()).forEach(fault ->
                assertEquals(Optional.of(fault),
                        PlatformFaultInjector.Fault.named(fault.spelling())));
        assertEquals(Optional.empty(), PlatformFaultInjector.Service.named("nothing-like-this"));
        assertEquals(Optional.empty(), PlatformFaultInjector.Fault.named("nothing-like-this"));
    }

    @Test
    @DisplayName("every instant comparison names which way being wrong is safe")
    void everycomparisonNamesItsSafeDirection() {
        assertEquals(ClockDisruptor.Comparison.values().length
                        * ClockDisruptor.Disruption.values().length,
                ClockDisruptor.everyDisruption().size(),
                "the disruptions are no longer the cross product, and a backward jump against"
                        + " retention is exactly the pair nobody would choose");
        assertEquals(ClockDisruptor.Conservative.DECIDE_LATE,
                ClockDisruptor.Comparison.THE_LEASE.conservative(),
                "deciding a lease early was called conservative, and an early lease is two workers"
                        + " writing at once");
        Arrays.stream(ClockDisruptor.Comparison.values()).forEach(comparison ->
                assertEquals(Optional.of(comparison),
                        ClockDisruptor.Comparison.named(comparison.spelling())));
        Arrays.stream(ClockDisruptor.Disruption.values()).forEach(disruption ->
                assertEquals(Optional.of(disruption),
                        ClockDisruptor.Disruption.named(disruption.spelling())));
        assertEquals(Optional.empty(), ClockDisruptor.Comparison.named("nothing-like-this"));
        assertEquals(Optional.empty(), ClockDisruptor.Disruption.named("nothing-like-this"));
    }

    @Test
    @DisplayName("a key ring kept for less than a token plus the skew is refused by the relation")
    void therelationRefusesARingThatStrandsAToken() {
        assertTrue(ClockDisruptor.priorRetentionCoversTheSkew(960_000, 900_000, 5_000),
                "the committed values were called insufficient, and they are not");
        assertTrue(!ClockDisruptor.priorRetentionCoversTheSkew(900_000, 900_000, 5_000),
                "a ring kept for exactly a token's lifetime was called sufficient, and a rotation"
                        + " under any skew at all strands a token signed at the last moment");
    }
}
