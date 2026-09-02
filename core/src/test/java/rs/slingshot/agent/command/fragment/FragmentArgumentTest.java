// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the six fragment arguments refuse, and how each refusal is said.
 *
 * <p>Separated from the suite that runs them because these are questions about the argument alone:
 * every one of them is answered before a repository is touched, and proving that here is what says
 * they are answered there. A refusal names the element or the member it is about and never the
 * value, because a refusal is written into a log other people read.</p>
 */
final class FragmentArgumentTest {

    private static final AgentContract CONTRACT = contract();

    private static final String LIBRARY = "/content/dam/site";

    private static final String MODEL = "/conf/site/settings/dam/cfm/models/article";

    @Test
    @DisplayName("elements are text or a list of text, and anything else is refused by name")
    void anelementIsTextOrAListOfIt() {
        assertEquals(FragmentElements.Refusal.NOT_A_DOCUMENT,
                refusedElements(new DocumentValue.Text("headline")).refusal());
        final SequencedMap<String, DocumentValue> whole = new LinkedHashMap<>();
        whole.put("headline", new DocumentValue.Whole(1));
        final FragmentElements.Refused refused =
                refusedElements(new DocumentValue.Mapping(whole));
        assertEquals(FragmentElements.Refusal.VALUE_REJECTED, refused.refusal());
        assertTrue(refused.detail().contains("headline"),
                "the refusal does not name the element it is about");
        final SequencedMap<String, DocumentValue> mixed = new LinkedHashMap<>();
        mixed.put("tags", new DocumentValue.Sequence(
                List.of(new DocumentValue.Text("a"), new DocumentValue.Whole(2))));
        assertEquals(FragmentElements.Refusal.VALUE_REJECTED,
                refusedElements(new DocumentValue.Mapping(mixed)).refusal(),
                "a list holding something that is not text was accepted, and the element would"
                        + " read back as a different element");
    }

    @Test
    @DisplayName("an element name, an element count and a value length are each held to the contract")
    void thethreeElementBoundsAreTheContracts() {
        final SequencedMap<String, DocumentValue> named = new LinkedHashMap<>();
        named.put("a".repeat((int) CONTRACT.value(
                ContractLimit.MAXIMUM_CONTENT_FRAGMENT_ELEMENT_NAME_BYTES) + 1),
                new DocumentValue.Text("value"));
        assertEquals(FragmentElements.Refusal.NAME_TOO_LONG,
                refusedElements(new DocumentValue.Mapping(named)).refusal());
        final SequencedMap<String, DocumentValue> many = new LinkedHashMap<>();
        IntStream.rangeClosed(0,
                        (int) CONTRACT.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_ELEMENTS))
                .forEach(index -> many.put("element" + index, new DocumentValue.Text("value")));
        assertEquals(FragmentElements.Refusal.TOO_MANY_ELEMENTS,
                refusedElements(new DocumentValue.Mapping(many)).refusal());
        final SequencedMap<String, DocumentValue> long_ = new LinkedHashMap<>();
        long_.put("headline", new DocumentValue.Text("a".repeat(
                (int) CONTRACT.value(ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES) + 1)));
        assertEquals(FragmentElements.Refusal.VALUE_TOO_LONG,
                refusedElements(new DocumentValue.Mapping(long_)).refusal());
        final SequencedMap<String, DocumentValue> values = new LinkedHashMap<>();
        values.put("tags", new DocumentValue.Sequence(IntStream.rangeClosed(0,
                        (int) CONTRACT.value(
                                ContractLimit.MAXIMUM_CONTENT_FRAGMENT_ELEMENT_VALUES))
                .mapToObj(index -> (DocumentValue) new DocumentValue.Text("tag" + index))
                .toList()));
        assertEquals(FragmentElements.Refusal.TOO_MANY_VALUES,
                refusedElements(new DocumentValue.Mapping(values)).refusal());
    }

    @Test
    @DisplayName("an argument naming no elements changes none of them, rather than clearing them")
    void namingNoElementsChangesNone() {
        final FragmentElements.Outcome read =
                FragmentElements.of(new DocumentValue.Mapping(new LinkedHashMap<>()), CONTRACT);
        assertTrue(assertInstanceOf(FragmentElements.Held.class, read,
                        "an argument naming no elements was refused").elements().isEmpty(),
                "an argument naming no elements was read as naming some, and a change that named"
                        + " none would empty the fragment it was asked to leave alone");
    }

    @Test
    @DisplayName("a creation needs a parent, a name and a model, and refuses a member nobody declared")
    void acreationTakesExactlyItsOwnMembers() {
        assertEquals(CreateContentFragmentCommand.Refusal.NOT_A_DOCUMENT,
                refusedCreation(new DocumentValue.Text("article")).refusal());
        final SequencedMap<String, DocumentValue> absent = new LinkedHashMap<>();
        absent.put(CreateContentFragmentCommand.PARENT_PATH, new DocumentValue.Text(LIBRARY));
        absent.put(CreateContentFragmentCommand.NAME, new DocumentValue.Text("article"));
        assertEquals(CreateContentFragmentCommand.Refusal.MEMBER_ABSENT,
                refusedCreation(new DocumentValue.Mapping(absent)).refusal());
        final SequencedMap<String, DocumentValue> unknown = creation("article");
        unknown.put("model_name", new DocumentValue.Text("article"));
        assertEquals(CreateContentFragmentCommand.Refusal.MEMBER_UNKNOWN,
                refusedCreation(new DocumentValue.Mapping(unknown)).refusal());
    }

    @Test
    @DisplayName("a creation refuses a relative path, an empty name and an oversized title")
    void acreationHoldsEachMemberToItsOwnShape() {
        final SequencedMap<String, DocumentValue> relative = creation("article");
        relative.put(CreateContentFragmentCommand.PARENT_PATH,
                new DocumentValue.Text("content/dam/site"));
        assertEquals(CreateContentFragmentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusedCreation(new DocumentValue.Mapping(relative)).refusal());
        assertEquals(CreateContentFragmentCommand.Refusal.NAME_REJECTED,
                refusedCreation(new DocumentValue.Mapping(creation("with/slash"))).refusal(),
                "a name holding a path separator was accepted, and the fragment would land"
                        + " somewhere other than under the parent the caller named");
        final SequencedMap<String, DocumentValue> titled = creation("article");
        titled.put(CreateContentFragmentCommand.TITLE, new DocumentValue.Text("a".repeat(
                (int) CONTRACT.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES) + 1)));
        assertEquals(CreateContentFragmentCommand.Refusal.TITLE_TOO_LONG,
                refusedCreation(new DocumentValue.Mapping(titled)).refusal());
        final SequencedMap<String, DocumentValue> elements = creation("article");
        elements.put(FragmentElements.ARGUMENT_MEMBER, new DocumentValue.Text("headline"));
        assertEquals(CreateContentFragmentCommand.Refusal.ELEMENTS_REJECTED,
                refusedCreation(new DocumentValue.Mapping(elements)).refusal());
    }

    @Test
    @DisplayName("a creation with no title carries none, rather than one that is empty")
    void acreationWithoutATitleCarriesNone() {
        final CreateContentFragmentCommand held = assertInstanceOf(
                CreateContentFragmentCommand.Held.class,
                CreateContentFragmentCommand.of(new DocumentValue.Mapping(creation("article")),
                        CONTRACT), "the argument was refused").command();
        assertEquals(CreateContentFragmentCommand.NO_TITLE, held.title(),
                "a fragment nobody titled was given an empty title, which reads as a fragment"
                        + " called the empty string rather than one known by its own name");
        assertEquals(LIBRARY + "/article", held.targetPath());
    }

    @Test
    @DisplayName("a content change refuses a variation that is not one node's own name")
    void achangeHoldsTheVariationToANodeName() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdateContentFragmentCommand.FRAGMENT_PATH,
                new DocumentValue.Text(LIBRARY + "/article"));
        members.put(UpdateContentFragmentCommand.VARIATION_NAME,
                new DocumentValue.Text("de/formal"));
        assertEquals(UpdateContentFragmentCommand.Refusal.VARIATION_NAME_REJECTED,
                assertInstanceOf(UpdateContentFragmentCommand.Refused.class,
                        UpdateContentFragmentCommand.of(new DocumentValue.Mapping(members),
                                CONTRACT), "a variation holding a path separator was accepted")
                        .refusal());
        final SequencedMap<String, DocumentValue> none = new LinkedHashMap<>();
        none.put(UpdateContentFragmentCommand.FRAGMENT_PATH,
                new DocumentValue.Text(LIBRARY + "/article"));
        assertEquals(FragmentHandlers.MASTER_VARIATION,
                assertInstanceOf(UpdateContentFragmentCommand.Held.class,
                        UpdateContentFragmentCommand.of(new DocumentValue.Mapping(none), CONTRACT),
                        "the argument was refused").command().variation(),
                "a change naming no variation did not mean the master one, which is the only"
                        + " variation every fragment has");
    }

    @Test
    @DisplayName("a content change refuses what is not a document, an unknown member and a bad path")
    void achangeTakesExactlyItsOwnMembers() {
        assertEquals(UpdateContentFragmentCommand.Refusal.NOT_A_DOCUMENT,
                refusedChange(new DocumentValue.Text("article")).refusal());
        assertEquals(UpdateContentFragmentCommand.Refusal.MEMBER_ABSENT,
                refusedChange(new DocumentValue.Mapping(new LinkedHashMap<>())).refusal());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(UpdateContentFragmentCommand.FRAGMENT_PATH,
                new DocumentValue.Text(LIBRARY + "/article"));
        unknown.put("element_names", new DocumentValue.Text("headline"));
        assertEquals(UpdateContentFragmentCommand.Refusal.MEMBER_UNKNOWN,
                refusedChange(new DocumentValue.Mapping(unknown)).refusal());
        final SequencedMap<String, DocumentValue> relative = new LinkedHashMap<>();
        relative.put(UpdateContentFragmentCommand.FRAGMENT_PATH,
                new DocumentValue.Text("content/dam/site/article"));
        assertEquals(UpdateContentFragmentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusedChange(new DocumentValue.Mapping(relative)).refusal());
        final SequencedMap<String, DocumentValue> titled = new LinkedHashMap<>();
        titled.put(UpdateContentFragmentCommand.FRAGMENT_PATH,
                new DocumentValue.Text(LIBRARY + "/article"));
        titled.put(UpdateContentFragmentCommand.TITLE, new DocumentValue.Whole(1));
        assertEquals(UpdateContentFragmentCommand.Refusal.TITLE_TOO_LONG,
                refusedChange(new DocumentValue.Mapping(titled)).refusal(),
                "a title that is not text was accepted");
        final SequencedMap<String, DocumentValue> elements = new LinkedHashMap<>();
        elements.put(UpdateContentFragmentCommand.FRAGMENT_PATH,
                new DocumentValue.Text(LIBRARY + "/article"));
        elements.put(FragmentElements.ARGUMENT_MEMBER, new DocumentValue.Text("headline"));
        assertEquals(UpdateContentFragmentCommand.Refusal.ELEMENTS_REJECTED,
                refusedChange(new DocumentValue.Mapping(elements)).refusal());
    }

    @Test
    @DisplayName("an experience creation refuses a missing variation, a bad name and a bad template")
    void anexperienceCreationTakesExactlyItsOwnMembers() {
        assertEquals(CreateExperienceFragmentCommand.Refusal.NOT_A_DOCUMENT,
                refusedExperience(new DocumentValue.Text("promo")).refusal());
        final SequencedMap<String, DocumentValue> absent = experience("promo");
        absent.remove(CreateExperienceFragmentCommand.VARIATION_NAME);
        assertEquals(CreateExperienceFragmentCommand.Refusal.MEMBER_ABSENT,
                refusedExperience(new DocumentValue.Mapping(absent)).refusal());
        final SequencedMap<String, DocumentValue> unknown = experience("promo");
        unknown.put("live_copy", new DocumentValue.Text("no"));
        assertEquals(CreateExperienceFragmentCommand.Refusal.MEMBER_UNKNOWN,
                refusedExperience(new DocumentValue.Mapping(unknown)).refusal());
        final SequencedMap<String, DocumentValue> relative = experience("promo");
        relative.put(CreateExperienceFragmentCommand.TEMPLATE_PATH,
                new DocumentValue.Text("conf/site/template"));
        assertEquals(CreateExperienceFragmentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusedExperience(new DocumentValue.Mapping(relative)).refusal());
        assertEquals(CreateExperienceFragmentCommand.Refusal.NAME_REJECTED,
                refusedExperience(new DocumentValue.Mapping(experience(""))).refusal());
        final SequencedMap<String, DocumentValue> variation = experience("promo");
        variation.put(CreateExperienceFragmentCommand.VARIATION_NAME,
                new DocumentValue.Text("web/desktop"));
        assertEquals(CreateExperienceFragmentCommand.Refusal.VARIATION_NAME_REJECTED,
                refusedExperience(new DocumentValue.Mapping(variation)).refusal());
        final SequencedMap<String, DocumentValue> titled = experience("promo");
        titled.put(CreateExperienceFragmentCommand.TITLE, new DocumentValue.Text("a".repeat(
                (int) CONTRACT.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES) + 1)));
        assertEquals(CreateExperienceFragmentCommand.Refusal.TITLE_TOO_LONG,
                refusedExperience(new DocumentValue.Mapping(titled)).refusal());
    }

    @Test
    @DisplayName("an experience creation names both the fragment and the variation it will hold")
    void anexperienceCreationNamesBothAddresses() {
        final CreateExperienceFragmentCommand held = assertInstanceOf(
                CreateExperienceFragmentCommand.Held.class,
                CreateExperienceFragmentCommand.of(new DocumentValue.Mapping(experience("promo")),
                        CONTRACT), "the argument was refused").command();
        assertEquals("/content/experience-fragments/site/promo", held.targetPath());
        assertEquals("/content/experience-fragments/site/promo/web", held.variationPath(),
                "the variation's address is not inside the fragment, and a caller addressing it"
                        + " next would find nothing there");
    }

    @Test
    @DisplayName("an experience change refuses an unknown member, a bad path and a bad change")
    void anexperienceChangeTakesExactlyItsOwnMembers() {
        assertEquals(UpdateExperienceFragmentCommand.Refusal.NOT_A_DOCUMENT,
                refusedVariation(new DocumentValue.Text("web")).refusal());
        assertEquals(UpdateExperienceFragmentCommand.Refusal.MEMBER_ABSENT,
                refusedVariation(new DocumentValue.Mapping(new LinkedHashMap<>())).refusal());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(UpdateExperienceFragmentCommand.VARIATION_PATH,
                new DocumentValue.Text("/content/experience-fragments/site/promo/web"));
        unknown.put("fragment_path", new DocumentValue.Text("/content"));
        assertEquals(UpdateExperienceFragmentCommand.Refusal.MEMBER_UNKNOWN,
                refusedVariation(new DocumentValue.Mapping(unknown)).refusal());
        final SequencedMap<String, DocumentValue> relative = new LinkedHashMap<>();
        relative.put(UpdateExperienceFragmentCommand.VARIATION_PATH,
                new DocumentValue.Text("content/experience-fragments/site/promo/web"));
        assertEquals(UpdateExperienceFragmentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusedVariation(new DocumentValue.Mapping(relative)).refusal());
        final SequencedMap<String, DocumentValue> titled = new LinkedHashMap<>();
        titled.put(UpdateExperienceFragmentCommand.VARIATION_PATH,
                new DocumentValue.Text("/content/experience-fragments/site/promo/web"));
        titled.put(UpdateExperienceFragmentCommand.TITLE, new DocumentValue.Text("a".repeat(
                (int) CONTRACT.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES) + 1)));
        assertEquals(UpdateExperienceFragmentCommand.Refusal.TITLE_TOO_LONG,
                refusedVariation(new DocumentValue.Mapping(titled)).refusal());
        final SequencedMap<String, DocumentValue> bare = new LinkedHashMap<>();
        bare.put(UpdateExperienceFragmentCommand.VARIATION_PATH,
                new DocumentValue.Text("/content/experience-fragments/site/promo/web"));
        final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
        properties.put("campaign", new DocumentValue.Text("spring"));
        bare.put(rs.slingshot.agent.command.mutation.PropertyChange.PROPERTIES,
                new DocumentValue.Mapping(properties));
        assertEquals(UpdateExperienceFragmentCommand.Refusal.CHANGE_REJECTED,
                refusedVariation(new DocumentValue.Mapping(bare)).refusal(),
                "a value with no cardinality beside it was written");
    }

    @Test
    @DisplayName("a deletion refuses what is not a document, a missing policy and an unknown one")
    void adeletionNeedsAPathAndAPolicy() {
        assertEquals(FragmentDeletion.Refusal.NOT_A_DOCUMENT,
                refusedDeletion(new DocumentValue.Text(LIBRARY)).refusal());
        final SequencedMap<String, DocumentValue> absent = new LinkedHashMap<>();
        absent.put(FragmentDeletion.FRAGMENT_PATH, new DocumentValue.Text(LIBRARY + "/article"));
        assertEquals(FragmentDeletion.Refusal.MEMBER_ABSENT,
                refusedDeletion(new DocumentValue.Mapping(absent)).refusal());
        final SequencedMap<String, DocumentValue> unknown = deletion("ignore_references");
        unknown.put("cascade", new DocumentValue.Text("yes"));
        assertEquals(FragmentDeletion.Refusal.MEMBER_UNKNOWN,
                refusedDeletion(new DocumentValue.Mapping(unknown)).refusal());
        final SequencedMap<String, DocumentValue> relative = deletion("ignore_references");
        relative.put(FragmentDeletion.FRAGMENT_PATH, new DocumentValue.Text("content/dam"));
        assertEquals(FragmentDeletion.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusedDeletion(new DocumentValue.Mapping(relative)).refusal());
        assertEquals(FragmentDeletion.Refusal.UNKNOWN_REFERENCE_POLICY,
                refusedDeletion(new DocumentValue.Mapping(deletion("cascade"))).refusal());
        final SequencedMap<String, DocumentValue> whole = deletion("ignore_references");
        whole.put(rs.slingshot.agent.command.mutation.ReferencePolicy.ARGUMENT_MEMBER,
                new DocumentValue.Whole(1));
        assertEquals(FragmentDeletion.Refusal.UNKNOWN_REFERENCE_POLICY,
                refusedDeletion(new DocumentValue.Mapping(whole)).refusal());
    }

    private static FragmentElements.Refused refusedElements(DocumentValue elements) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FragmentElements.ARGUMENT_MEMBER, elements);
        return assertInstanceOf(FragmentElements.Refused.class,
                FragmentElements.of(new DocumentValue.Mapping(members), CONTRACT),
                "elements this contract does not write were accepted");
    }

    private static CreateContentFragmentCommand.Refused refusedCreation(DocumentValue argument) {
        return assertInstanceOf(CreateContentFragmentCommand.Refused.class,
                CreateContentFragmentCommand.of(argument, CONTRACT),
                "an argument this command does not take was accepted");
    }

    private static UpdateContentFragmentCommand.Refused refusedChange(DocumentValue argument) {
        return assertInstanceOf(UpdateContentFragmentCommand.Refused.class,
                UpdateContentFragmentCommand.of(argument, CONTRACT),
                "an argument this command does not take was accepted");
    }

    private static CreateExperienceFragmentCommand.Refused refusedExperience(
            DocumentValue argument) {
        return assertInstanceOf(CreateExperienceFragmentCommand.Refused.class,
                CreateExperienceFragmentCommand.of(argument, CONTRACT),
                "an argument this command does not take was accepted");
    }

    private static UpdateExperienceFragmentCommand.Refused refusedVariation(
            DocumentValue argument) {
        return assertInstanceOf(UpdateExperienceFragmentCommand.Refused.class,
                UpdateExperienceFragmentCommand.of(argument, CONTRACT),
                "an argument this command does not take was accepted");
    }

    private static FragmentDeletion.Refused refusedDeletion(DocumentValue argument) {
        return assertInstanceOf(FragmentDeletion.Refused.class,
                FragmentDeletion.of(argument, CONTRACT),
                "an argument this command does not take was accepted");
    }

    private static SequencedMap<String, DocumentValue> creation(String name) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateContentFragmentCommand.PARENT_PATH, new DocumentValue.Text(LIBRARY));
        members.put(CreateContentFragmentCommand.NAME, new DocumentValue.Text(name));
        members.put(CreateContentFragmentCommand.MODEL_PATH, new DocumentValue.Text(MODEL));
        return members;
    }

    private static SequencedMap<String, DocumentValue> experience(String name) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateExperienceFragmentCommand.PARENT_PATH,
                new DocumentValue.Text("/content/experience-fragments/site"));
        members.put(CreateExperienceFragmentCommand.NAME, new DocumentValue.Text(name));
        members.put(CreateExperienceFragmentCommand.TEMPLATE_PATH,
                new DocumentValue.Text("/conf/site/settings/wcm/templates/fragment"));
        members.put(CreateExperienceFragmentCommand.VARIATION_NAME,
                new DocumentValue.Text("web"));
        return members;
    }

    private static SequencedMap<String, DocumentValue> deletion(String policy) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FragmentDeletion.FRAGMENT_PATH, new DocumentValue.Text(LIBRARY + "/article"));
        members.put(rs.slingshot.agent.command.mutation.ReferencePolicy.ARGUMENT_MEMBER,
                new DocumentValue.Text(policy));
        return members;
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
