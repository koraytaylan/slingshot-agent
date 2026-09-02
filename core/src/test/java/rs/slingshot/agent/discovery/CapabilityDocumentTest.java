// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The one document a client reads before it sends anything, and everything it has to be able to
 * tell apart.
 *
 * <p>The bound is stated by the suite rather than taken from the contract, because proving what a
 * bound does needs a document that crosses it and a document of two mebibytes proves the same thing
 * at two mebibytes of cost. That the production bound is the contract's own is proved beside the
 * servlet, where the document is actually built.</p>
 */
final class CapabilityDocumentTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/capability-document");

    private static final Path SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/discovery/capabilities.json");

    private static final AgentContract CONTRACT = contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    private static final long PROTOCOL_BOUND =
            CONTRACT.value(ContractLimit.MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES);

    @Test
    @DisplayName("no contracts is an empty array rather than an absent member")
    void anEmptyListIsStated() {
        final String rendered = rendered("no-contracts.json",
                AdvertisedCapabilities.ContinuationAuthority.NOT_READY);
        assertTrue(rendered.contains("\"command_contracts\":[]"), rendered);
        assertTrue(rendered.contains("\"continuation_authority_ready\":false"), rendered);
    }

    @Test
    @DisplayName("contracts are emitted in wire order however they arrived")
    void contractsAreEmittedInWireOrder() {
        final String one = rendered("one-contract.json",
                AdvertisedCapabilities.ContinuationAuthority.READY);
        assertTrue(one.contains("\"command_wire_name\":\"query_paths\""), one);
        final String several = rendered("out-of-order.json",
                AdvertisedCapabilities.ContinuationAuthority.READY);
        assertEquals(List.of("create_page", "list_child_pages", "query_paths"),
                wireNamesIn(several),
                "the contracts were emitted in the order they arrived rather than in wire order");
    }

    @Test
    @DisplayName("two contracts answering to one wire name are refused, naming it")
    void aDuplicateWireNameIsRefused() {
        final CapabilityDocument.Refused refused = assertInstanceOf(CapabilityDocument.Refused.class,
                CapabilityDocument.of(capabilities("duplicate-wire-name.json",
                        AdvertisedCapabilities.ContinuationAuthority.READY), PROTOCOL_BOUND),
                "two contracts under one name were advertised");
        assertEquals(CapabilityDocument.Refusal.DUPLICATE_WIRE_NAME, refused.refusal());
        assertTrue(refused.detail().contains("query_paths"), refused.detail());
    }

    @Test
    @DisplayName("readiness is read from an authority rather than fixed in the document")
    void readinessIsObserved() {
        final AtomicInteger asked = new AtomicInteger();
        final AdvertisedCapabilities.Readiness alternating = () -> asked.getAndIncrement() % 2 == 0
                ? AdvertisedCapabilities.ContinuationAuthority.READY
                : AdvertisedCapabilities.ContinuationAuthority.NOT_READY;
        assertTrue(renderedWith(alternating).contains("\"continuation_authority_ready\":true"));
        assertTrue(renderedWith(alternating).contains("\"continuation_authority_ready\":false"));
        assertTrue(renderedWith(alternating).contains("\"continuation_authority_ready\":true"));
        assertEquals(3, asked.get(), "the authority was not asked once per document");
    }

    @Test
    @DisplayName("the document holds at exactly the bound and is refused one byte past it")
    void theDocumentBoundHoldsAtBothSides() {
        final AdvertisedCapabilities capabilities = capabilities("out-of-order.json",
                AdvertisedCapabilities.ContinuationAuthority.READY);
        final long length = held(capabilities, PROTOCOL_BOUND).bytes().length;
        assertInstanceOf(CapabilityDocument.Held.class,
                CapabilityDocument.of(capabilities, length),
                "a document of exactly the bound was refused");
        final CapabilityDocument.Refused refused = assertInstanceOf(CapabilityDocument.Refused.class,
                CapabilityDocument.of(capabilities, length - 1),
                "a document past the bound was answered");
        assertEquals(CapabilityDocument.Refusal.PAST_THE_DOCUMENT_BOUND, refused.refusal());
        assertTrue(refused.detail().contains("3 contracts"), refused.detail());
    }

    @Test
    @DisplayName("the document is the shape the client's discovery expects, field by field")
    void theDocumentIsTheShapeTheClientExpects() {
        final DocumentValue.Mapping document = read(rendered("one-contract.json",
                AdvertisedCapabilities.ContinuationAuthority.READY));
        assertEquals(CapabilityDocument.MEMBERS.stream().sorted().toList(),
                List.copyOf(document.members().keySet()).stream().sorted().toList());
        assertEquals(1, assertInstanceOf(DocumentValue.Whole.class,
                document.member(CapabilityDocument.GENERATION).orElseThrow()).value());
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                document.member(CapabilityDocument.AUTHORITY_READY).orElseThrow());
        final DocumentValue.Mapping contract = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Sequence.class,
                        document.member(CapabilityDocument.CONTRACTS).orElseThrow())
                        .items().getFirst());
        assertEquals(CommandContractIdentity.MEMBERS.stream().sorted().toList(),
                List.copyOf(contract.members().keySet()).stream().sorted().toList(),
                "an advertised contract is not the five-field identity the client reads");
    }

    @Test
    @DisplayName("the committed schema and this model name the same members in both directions")
    void theSchemaAndTheModelAgree() {
        final DocumentValue.Mapping schema = assertInstanceOf(DocumentValue.Mapping.class,
                document(new String(bytes(SCHEMA), StandardCharsets.UTF_8).strip()));
        assertEquals(CapabilityDocument.MEMBERS.stream().sorted().toList(),
                List.copyOf(assertInstanceOf(DocumentValue.Mapping.class,
                        schema.member("properties").orElseThrow()).members().keySet()).stream()
                        .sorted().toList());
    }

    private static List<String> wireNamesIn(String rendered) {
        return assertInstanceOf(DocumentValue.Sequence.class,
                read(rendered).member(CapabilityDocument.CONTRACTS).orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Mapping.class, item))
                .map(contract -> assertInstanceOf(DocumentValue.Text.class,
                        contract.member(CommandContractIdentity.WIRE_NAME).orElseThrow()).value())
                .toList();
    }

    private static String renderedWith(AdvertisedCapabilities.Readiness readiness) {
        return held(capabilities("no-contracts.json", readiness.observe()), PROTOCOL_BOUND)
                .render();
    }

    private static String rendered(String fixture,
                                   AdvertisedCapabilities.ContinuationAuthority authority) {
        return held(capabilities(fixture, authority), PROTOCOL_BOUND).render();
    }

    private static CapabilityDocument held(AdvertisedCapabilities capabilities, long bound) {
        return assertInstanceOf(CapabilityDocument.Held.class,
                CapabilityDocument.of(capabilities, bound),
                "the document was refused").document();
    }

    private static AdvertisedCapabilities capabilities(String fixture,
            AdvertisedCapabilities.ContinuationAuthority authority) {
        return new AdvertisedCapabilities(generation(), digest("a canonical contract"),
                contracts(fixture), authority, digest("a transport contract"));
    }

    private static List<CommandContractIdentity> contracts(String fixture) {
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                document(new String(bytes(FIXTURES.resolve(fixture)), StandardCharsets.UTF_8)));
        return assertInstanceOf(DocumentValue.Sequence.class,
                document.member("contract").orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(CommandContractIdentity.Held.class,
                        CommandContractIdentity.of(item,
                                CommandContractIdentity.Bounds.from(CONTRACT)),
                        "a fixture carries an identity that is not one").identity())
                .toList();
    }

    private static DocumentValue.Mapping read(String rendered) {
        return assertInstanceOf(DocumentValue.Mapping.class, document(rendered));
    }

    private static DocumentValue document(String rendered) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(rendered.getBytes(StandardCharsets.UTF_8),
                        DOCUMENT_BOUNDS),
                "the document is not one this reader accepts").value();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation is not one").generation();
    }

    private static DigestValue digest(String seed) {
        return rs.slingshot.agent.digest.Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
