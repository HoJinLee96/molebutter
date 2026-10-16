package cc.ataglace.molebutter.coupang.api;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
import static org.assertj.core.api.Assertions.*;

class CoupangMediaIdentityTest {
    private final String option = UUID.randomUUID().toString(), first = UUID.randomUUID().toString(),
            second = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
    private Document document(List<Image> images, List<Content> contents) {
        return new Document(Map.of(),
                List.of(new Option(option, "", Map.of(), List.of(), images, contents, List.of(), List.of(), null)),
                Map.of(), Map.of(), List.of());
    }
    @Test
    void duplicatesAndNewRowsKeepDistinctIdsAndTheLatestMediaRoles() {
        String url = "https://images.example.test/same.png";
        var previous = document(
                List.of(new Image(first, null, url, "REPRESENTATION", 0), new Image(second, null, url, "DETAIL", 1)),
                List.of(new Content(first, "HTML", "TEXT", "same", null),
                        new Content(second, "HTML", "IMAGE", "same", null)));
        var fresh = document(
                List.of(new Image(first, null, url + "?new", "REPRESENTATION", 0),
                        new Image(UUID.randomUUID().toString(), null, url, "DETAIL", 1),
                        new Image(UUID.randomUUID().toString(), null, url, "REPRESENTATION", 2)),
                List.of(new Content(first, "HTML", "TEXT", "new", null),
                        new Content(UUID.randomUUID().toString(), "HTML", "IMAGE", "same", null),
                        new Content(UUID.randomUUID().toString(), "HTML", "TEXT", "same", null)));
        var result = CoupangWorkingCopyDocuments
                .normalize(CoupangInputs.preserveMedia(fresh, previous, Map.of(), Map.of()), new ObjectMapper())
                .options().getFirst();
        assertThat(result.images()).extracting(Image::id).doesNotHaveDuplicates().contains(first, second);
        assertThat(result.images().getFirst().id()).isNotIn(first, second);
        assertThat(result.images().get(1).id()).isEqualTo(second);
        assertThat(result.contents()).extracting(Content::id).doesNotHaveDuplicates().contains(first, second);
        assertThat(result.contents().getFirst().id()).isNotIn(first, second);
        assertThat(result.contents().get(1).id()).isEqualTo(second);
        assertThat(result.contents().get(1).detailType()).isEqualTo("IMAGE");
    }
    @Test
    void onlyTheExactPublishedUrlRetainsTheLocalAssetReference() {
        String token = "b".repeat(64), published = "https://app.example.test/coupang/images/" + token;
        var previous = document(List.of(new Image(first, asset, "/api/coupang/assets/" + asset, "REPRESENTATION", 0)),
                List.of(new Content(first, "IMAGE", "IMAGE", "/api/coupang/assets/" + asset, asset)));
        for (String url : List.of(published, "https://other.example.test/coupang/images/" + token)) {
            var fresh = document(List.of(new Image(second, null, url, "REPRESENTATION", 0)),
                    List.of(new Content(second, "IMAGE", "IMAGE", url, null)));
            var result = CoupangInputs.preserveMedia(fresh, previous, Map.of(asset, token), Map.of(asset, published))
                    .options().getFirst();
            assertThat(result.images().getFirst().id()).isEqualTo(first);
            assertThat(result.contents().getFirst().id()).isEqualTo(first);
            assertThat(result.images().getFirst().assetId()).isEqualTo(url.equals(published) ? asset : null);
            assertThat(result.contents().getFirst().assetId()).isEqualTo(url.equals(published) ? asset : null);
        }
    }
}
