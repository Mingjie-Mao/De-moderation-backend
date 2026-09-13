package com.campusguard.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What every media backend has to do, asserted once and inherited by each.
 *
 * <p>The point of a shared contract rather than two test classes. The reason
 * {@link MediaStorage} exists is that a deployment can change where images live
 * without the upload path changing, and that promise is only worth anything if
 * the backends actually behave the same. Two independently written test classes
 * drift: each ends up asserting what its own implementation happens to do, and
 * the day the backend is switched is the day the difference is discovered.
 *
 * <p>So the subclasses supply a backend and nothing else. A behaviour that only
 * one of them can manage does not belong in the interface.
 */
abstract class MediaStorageContract {

    protected abstract MediaStorage storage();

    private static final String TYPE = "image/png";

    @Test
    void storesBytesAndGivesThemBack() {
        MediaStorage storage = storage();
        byte[] bytes = "the actual image".getBytes(StandardCharsets.UTF_8);

        storage.put(key("a"), bytes, TYPE);

        assertThat(storage.get(key("a"))).contains(bytes);
    }

    /**
     * A missing object is an answer, not a fault. It is also the exact state the
     * ephemeral container filesystem left behind, so the read path has to be able
     * to render it as a missing image rather than a failed request.
     */
    @Test
    void reportsAnUnknownKeyAsAbsentRatherThanFailing() {
        assertThat(storage().get(key("never-written"))).isEmpty();
    }

    /**
     * The silent version of this bug is one user's post showing another user's
     * photo, so an overwrite has to be loud.
     */
    @Test
    void refusesToOverwriteAKeyThatIsAlreadyTaken() {
        MediaStorage storage = storage();
        storage.put(key("taken"), "first".getBytes(StandardCharsets.UTF_8), TYPE);

        assertThatThrownBy(() -> storage.put(key("taken"), "second".getBytes(StandardCharsets.UTF_8), TYPE))
                .isInstanceOf(MediaStorageException.class);

        assertThat(storage.get(key("taken"))).contains("first".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The return value is what the sweep logs, so it has to mean "this call
     * removed something" and not "there is now nothing there".
     */
    @Test
    void saysWhetherADeleteActuallyRemovedAnything() {
        MediaStorage storage = storage();
        storage.put(key("doomed"), "bytes".getBytes(StandardCharsets.UTF_8), TYPE);

        assertThat(storage.delete(key("doomed"))).isTrue();
        assertThat(storage.delete(key("doomed"))).isFalse();
        assertThat(storage.get(key("doomed"))).isEmpty();
    }

    /**
     * The sweep asks this about every row it checks, so it has to be cheap and it
     * has to agree with {@link MediaStorage#get} on every backend. A backend that
     * answered from a stale listing, or that treated a directory as an object,
     * would have the sweep report healthy rows as missing.
     */
    @Test
    void answersWhetherAnObjectIsThereWithoutFetchingIt() {
        MediaStorage storage = storage();
        assertThat(storage.exists(key("ghost"))).isFalse();

        storage.put(key("ghost"), "bytes".getBytes(StandardCharsets.UTF_8), TYPE);
        assertThat(storage.exists(key("ghost"))).isTrue();
        assertThat(storage.get(key("ghost"))).isPresent();

        storage.delete(key("ghost"));
        assertThat(storage.exists(key("ghost"))).isFalse();
        assertThat(storage.get(key("ghost"))).isEmpty();
    }

    @Test
    void listsNothingWhenNothingIsStored() {
        assertThat(storage().list(null, 10)).isEmpty();
    }

    /**
     * The sweep pages with nothing but the previous page's last key, which only
     * works if the order is total and stable. Directory iteration order is not,
     * which is why the filesystem backend sorts.
     */
    @Test
    void pagesThroughKeysInOneStableOrder() {
        MediaStorage storage = storage();
        List<String> written = List.of(key("k1"), key("k2"), key("k3"), key("k4"), key("k5"));
        written.forEach(key -> storage.put(key, "x".getBytes(StandardCharsets.UTF_8), TYPE));

        List<String> first = storage.list(null, 2).stream().map(MediaStorage.StoredObject::key).toList();
        List<String> second = storage.list(first.getLast(), 2).stream()
                .map(MediaStorage.StoredObject::key).toList();
        List<String> third = storage.list(second.getLast(), 2).stream()
                .map(MediaStorage.StoredObject::key).toList();

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(1);

        List<String> paged = new java.util.ArrayList<>(first);
        paged.addAll(second);
        paged.addAll(third);
        assertThat(paged).containsExactlyElementsOf(written.stream().sorted().toList());
    }

    /**
     * Without an age the sweep cannot distinguish an object whose row was never
     * committed from one being uploaded at this moment, and it deletes the second
     * one.
     */
    @Test
    void listsWhenEachObjectWasWritten() {
        MediaStorage storage = storage();
        Instant before = Instant.now().minusSeconds(60);
        storage.put(key("dated"), "x".getBytes(StandardCharsets.UTF_8), TYPE);

        assertThat(storage.list(null, 10)).singleElement().satisfies(object -> {
            assertThat(object.key()).isEqualTo(key("dated"));
            assertThat(object.modifiedAt()).isAfter(before);
        });
    }

    @Test
    void refusesABlankKey() {
        assertThatThrownBy(() -> storage().get(" ")).isInstanceOf(MediaStorageException.class);
    }

    @Test
    void saysWhatItIs() {
        assertThat(storage().describe()).isNotBlank();
    }

    /**
     * Subclasses may namespace keys so that a shared backend — one MinIO
     * container across the S3 tests — does not leak state between tests.
     */
    protected String key(String name) {
        return name;
    }
}
