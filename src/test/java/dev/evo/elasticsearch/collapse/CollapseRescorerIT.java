package dev.evo.elasticsearch.collapse;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ESIntegTestCase;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.SUITE)
public class CollapseRescorerIT extends ESIntegTestCase {
    private static final String INDEX_NAME = "test_collapse";
    private static final String COLLAPSE_FIELD = "model_id";

    public void testEmptyIndex() throws IOException {
        createTestIndex(1);

        var queryBuilder = QueryBuilders.matchAllQuery();
        var response = client().prepareSearch(INDEX_NAME)
            .setSource(
                new SearchSourceBuilder()
                    .query(queryBuilder)
                    .ext(List.of(new CollapseSearchExtBuilder(COLLAPSE_FIELD)))
            )
            .get();

        assertSearchResponse(response);
    }

    public void testDefaultCollapsing() throws IOException {
        createAndPopulateTestIndex(1);

        var response = client().prepareSearch(INDEX_NAME)
            .setSource(
                new SearchSourceBuilder()
                    .query(rankQuery())
                    .ext(List.of(new CollapseSearchExtBuilder(COLLAPSE_FIELD)))
            )
            .get();

        assertSearchResponse(response);

        assertHitCount(response, 4);
        assertOrderedSearchHits(response, "7", "5", "3", "2");

        assertSearchHit(response, 1,
            new FieldsMatcher(List.of(
                new DocumentField("model_id", List.of(1L))
            ))
        );
        assertSearchHit(response, 2,
            new FieldsMatcher(List.of(
                new DocumentField("model_id", List.of())
            ))
        );
        assertSearchHit(response, 3,
            new FieldsMatcher(List.of(
                new DocumentField("model_id", List.of())
            ))
        );
        assertSearchHit(response, 4,
            new FieldsMatcher(List.of(
                new DocumentField("model_id", List.of(2L))
            ))
        );
    }

    public void testCollaplingSize() throws IOException {
        createAndPopulateTestIndex(1);

        var response = client().prepareSearch(INDEX_NAME)
            .setSource(
                new SearchSourceBuilder()
                    .query(rankQuery())
                    .ext(List.of(new CollapseSearchExtBuilder(COLLAPSE_FIELD)))
                    .size(2)
            )
            .get();

        assertSearchResponse(response);

        assertHitCount(response, 4);
        assertOrderedSearchHits(response, "7", "5");
    }

    public void testMerge() throws IOException {
        createAndPopulateTestIndex(2);

        // Ensure docs fell into different shards
        var response = client().prepareSearch(INDEX_NAME)
            .setQuery(QueryBuilders.idsQuery().addIds("1", "4"))
            .setPreference("_shards:1")
            .get();
        assertSearchResponse(response);
        assertHitCount(response, 2);

        response = client().prepareSearch(INDEX_NAME)
            .setQuery(QueryBuilders.idsQuery().addIds("7"))
            .setPreference("_shards:0")
            .get();
        assertSearchResponse(response);
        assertHitCount(response, 1);

        response = client().prepareSearch(INDEX_NAME)
            .setSource(
                new SearchSourceBuilder()
                    .query(rankQuery())
                    .ext(List.of(new CollapseSearchExtBuilder(COLLAPSE_FIELD)))
                    .size(2)
            )
            .get();

        assertSearchResponse(response);

        assertHitCount(response, 4);
        assertOrderedSearchHits(response, "7", "5");
    }

    private void createTestIndex(int numberOfShards) throws IOException {
        assertAcked(
            prepareCreate(INDEX_NAME)
                .setSettings(
                    Settings.builder().put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, numberOfShards)
                )
                .addMapping("_doc", testMapping())
        );
        ensureGreen(INDEX_NAME);
    }

    private void createAndPopulateTestIndex(int numberOfShards) throws IOException {
        createTestIndex(numberOfShards);
        for (var doc : testDocs()) {
            doc.setIndex(INDEX_NAME).setType("_doc").get();
        }
        refresh(INDEX_NAME);
    }

    private QueryBuilder rankQuery() {
        return QueryBuilders.functionScoreQuery(
            new FieldValueFactorFunctionBuilder("rank")
        );
    }

    private XContentBuilder testMapping() throws IOException {
        return jsonBuilder()
            .startObject()
                .startObject("_doc")
                    .startObject("properties")
                        .startObject(COLLAPSE_FIELD)
                            .field("type", "integer")
                        .endObject()
                        .startObject("rank")
                            .field("type", "float")
                        .endObject()
                    .endObject()
                .endObject()
            .endObject();
    }

    private List<IndexRequestBuilder> testDocs() {
        return List.of(
            client().prepareIndex()
                .setId("1")
                .setSource(
                    COLLAPSE_FIELD, 1,
                    "rank", 1.1F
                ),
            client().prepareIndex()
                .setId("2")
                .setSource(
                    COLLAPSE_FIELD, 2,
                    "rank", 1.2F
                ),
            client().prepareIndex()
                .setId("3")
                .setSource(
                    "rank", 1.3F
                ),
            client().prepareIndex()
                .setId("4")
                .setSource(
                    COLLAPSE_FIELD, 1,
                    "rank", 1.4F
                ),
            client().prepareIndex()
                .setId("5")
                .setSource(
                    "rank", 1.5F
                ),
            client().prepareIndex()
                .setId("6")
                .setSource(
                    COLLAPSE_FIELD, 2,
                    "rank", 0.6F
                ),
            client().prepareIndex()
                .setId("7")
                .setSource(
                    COLLAPSE_FIELD, 1,
                    "rank", 1.7F
                )
        );
    }

    static class FieldsMatcher extends BaseMatcher<SearchHit> {
        private final Map<String, DocumentField> fields;

        FieldsMatcher(List<DocumentField> fields) {
            this.fields = fields.stream()
                .collect(Collectors.toMap(DocumentField::getName, f -> f));
        }

        @Override
        public void describeTo(Description description) {
            description.appendValue(fields);
        }

        @Override
        public void describeMismatch(Object item, Description description) {
            if (item instanceof SearchHit) {
                final var hit = (SearchHit) item;
                item = hit.getFields();
            }
            super.describeMismatch(item, description);
        }

        @Override
        public boolean matches(Object item) {
            if (!(item instanceof SearchHit)) {
                return false;
            }
            final var hit = (SearchHit) item;
            final var hitFields = hit.getFields();
            return fields.equals(hitFields);
        }
    }
}
