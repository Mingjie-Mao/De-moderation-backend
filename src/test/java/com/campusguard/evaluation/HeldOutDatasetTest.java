package com.campusguard.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.ModerationDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The properties that make the held-out set worth running, checked as properties
 * rather than trusted.
 *
 * <p>A dataset is the one artefact in this project that no other test touches. It
 * is edited by hand, it is the denominator of every number published about the
 * system, and a mistake in it is invisible: a pair whose halves carry the same
 * label, or a sample copied out of the set the prompts were tuned against, would
 * simply make the scores wrong in a direction nobody could see. These assertions
 * are the difference between claiming the set has those properties and knowing
 * it does.
 */
class HeldOutDatasetTest {

    private static final String HELD_OUT = "docs/evaluation-heldout-samples.json";
    private static final String TUNING_SET = "docs/evaluation-samples.json";

    private final EvaluationDataset datasets = new EvaluationDataset(new ObjectMapper());

    @Test
    void everySampleIsHalfOfExactlyOnePair() throws IOException {
        List<LabelledSample> samples = load(HELD_OUT);

        assertThat(samples).isNotEmpty();
        assertThat(samples).allSatisfy(sample -> assertThat(sample.paired()).isTrue());

        Map<String, List<LabelledSample>> byPair = byPair(samples);
        assertThat(byPair.values()).allSatisfy(pair -> assertThat(pair).hasSize(2));
        assertThat(samples).hasSize(byPair.size() * 2);
    }

    /**
     * The point of a pair. Two halves with the same expected action are two
     * samples, and scoring them as a unit would measure nothing.
     */
    @Test
    void theTwoHalvesOfAPairAlwaysWantDifferentAnswers() throws IOException {
        byPair(load(HELD_OUT)).forEach((pairId, pair) ->
                assertThat(pair.get(0).expected())
                        .as("pair %s", pairId)
                        .isNotEqualTo(pair.get(1).expected()));
    }

    /**
     * The flaw this set exists to remove. In the 192-sample set the source of a
     * sample almost perfectly predicts its label, so part of a good score there
     * is a reward for noticing which half a sample came from. One source across
     * every label leaves nothing to notice.
     */
    @Test
    void provenanceCannotPredictTheLabel() throws IOException {
        List<LabelledSample> samples = load(HELD_OUT);

        assertThat(samples.stream().map(LabelledSample::provenance).distinct()).hasSize(1);
    }

    /**
     * Held out means held out. A sample shared with the set that `v2` and `v3`
     * were written against would make the estimate exactly as optimistic as the
     * one it is supposed to replace, and an id collision or a copied body is how
     * that happens in practice.
     */
    @Test
    void sharesNoSampleWithTheSetThePromptsWereTunedOn() throws IOException {
        List<LabelledSample> heldOut = load(HELD_OUT);
        List<LabelledSample> tuning = load(TUNING_SET);

        Set<String> tuningIds = tuning.stream().map(LabelledSample::id).collect(Collectors.toSet());
        Set<String> tuningBodies = tuning.stream().map(this::normalise).collect(Collectors.toSet());

        assertThat(heldOut).allSatisfy(sample -> {
            assertThat(tuningIds).doesNotContain(sample.id());
            assertThat(tuningBodies).doesNotContain(normalise(sample));
        });
    }

    @Test
    void hasNoRepeatedIdOrRepeatedBody() throws IOException {
        List<LabelledSample> samples = load(HELD_OUT);

        assertThat(samples.stream().map(LabelledSample::id).distinct()).hasSize(samples.size());
        assertThat(samples.stream().map(this::normalise).distinct()).hasSize(samples.size());
    }

    /**
     * A rule engine only works in the language its term list was written in, so a
     * set in one language would quietly measure something narrower than the forum
     * it is standing in for.
     */
    @Test
    void coversBothLanguagesTheForumRunsIn() throws IOException {
        List<LabelledSample> samples = load(HELD_OUT);

        long chinese = samples.stream().filter(sample -> hasHanCharacter(sample.body())).count();

        assertThat(chinese).isGreaterThan(samples.size() / 4);
        assertThat(samples.size() - chinese).isGreaterThan(samples.size() / 4);
    }

    /** All three actions, and enough of the rarest to say anything about it. */
    @Test
    void carriesEnoughOfEveryActionToScoreIt() throws IOException {
        Map<ModerationDecision, Long> counts = load(HELD_OUT).stream()
                .collect(Collectors.groupingBy(LabelledSample::expected, Collectors.counting()));

        assertThat(counts.keySet()).containsExactlyInAnyOrder(
                ModerationDecision.ALLOW, ModerationDecision.REMOVE, ModerationDecision.ESCALATE);
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(15));
    }

    /**
     * The note is what makes a label auditable by a person in seconds: for a
     * paired sample it says what the edit was and which part of the policy the
     * label follows from. Without it the set is one author's taste.
     */
    @Test
    void saysWhyEverySampleIsLabelledTheWayItIs() throws IOException {
        assertThat(load(HELD_OUT)).allSatisfy(sample ->
                assertThat(sample.note()).as("note for %s", sample.id()).isNotBlank());
    }

    private List<LabelledSample> load(String path) throws IOException {
        return datasets.load(path).samples();
    }

    private Map<String, List<LabelledSample>> byPair(List<LabelledSample> samples) {
        Map<String, List<LabelledSample>> byPair = new LinkedHashMap<>();
        samples.forEach(sample ->
                byPair.computeIfAbsent(sample.pairId(), key -> new java.util.ArrayList<>()).add(sample));
        return byPair;
    }

    private String normalise(LabelledSample sample) {
        return (sample.body() == null ? "" : sample.body()).replaceAll("\\s+", " ").trim();
    }

    private boolean hasHanCharacter(String text) {
        return text != null && text.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
