package dev.evo.elasticsearch.collapse;

import dev.evo.elasticsearch.collapse.rescore.CollapseRescorerBuilder;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.SearchProfileShardResults;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.tasks.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

public class CollapseRescoreFilter implements ActionFilter {
    public static Setting<Integer> COLLAPSE_RESCORE_FILTER_ORDER = Setting.intSetting(
        "collapse.rescore.filter.order", 10, Setting.Property.NodeScope
    );

    private final static String SCRIPT_SORT_FIELD_NAME = "_collapse_script_sort";

    @SuppressWarnings("unchecked")
    private final static Comparator<Object> ANY_COMPARATOR = (first, second) -> {
        if (first == null) {
            if (second == null) {
                return 0;
            }
            return -1;
        }
        if (second == null) {
            return 1;
        }
        return ((Comparable<Object>) first).compareTo(second);
    };

    static final class TopGroup {
        final int collapsedIx;
        Object sortValue;
        float score;

        TopGroup(int collapsedIx, Object sortValue, float score) {
            this.collapsedIx = collapsedIx;
            this.sortValue = sortValue;
            this.score = score;
        }
    }

    private final int order;

    public CollapseRescoreFilter(final Settings settings) {
        order = COLLAPSE_RESCORE_FILTER_ORDER.get(settings);
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> void apply(
        Task task,
        String action,
        Request request,
        ActionListener<Response> listener,
        ActionFilterChain<Request, Response> chain
    ) {
        if (!SearchAction.INSTANCE.name().equals(action)) {
            chain.proceed(task, action, request, listener);
            return;
        }

        final var searchRequest = (SearchRequest) request;
        final var source = searchRequest.source();
        final var origSize = source.size();
        final var origFrom = source.from();

        final var searchExtensions = source.ext();
        final var searchExt = searchExtensions.stream()
            .filter(ext -> ext.getWriteableName().equals(CollapseSearchExtBuilder.NAME))
            .findFirst();
        if (searchExt.isEmpty()) {
            chain.proceed(task, action, request, listener);
            return;
        }

        final var collapseExt = (CollapseSearchExtBuilder) searchExt.get();

        source.from(0);
        // Set size equal to window size thus we will get right number of docs after a merge
        final var size = collapseExt.windowSize();
        source.size(size);

        final var groupField = collapseExt.groupField();
        source.docValueField(groupField);

        final var sorts = collapseExt.getSorts();
        String tmpSortField = null;
        int tmpReverseMul = 1;
        if (!sorts.isEmpty()) {
            final var sort = sorts.get(0);
            if (sort.order() == SortOrder.DESC) {
                tmpReverseMul = -1;
            }
            if (sort instanceof FieldSortBuilder) {
                final var fieldSort = (FieldSortBuilder) sort;
                tmpSortField = fieldSort.getFieldName();
                source.docValueField(tmpSortField);
            } else if (sort instanceof ScriptSortBuilder) {
                tmpSortField = SCRIPT_SORT_FIELD_NAME;
                final var scriptSort = (ScriptSortBuilder) sort;
                source.scriptField(tmpSortField, scriptSort.script());
            }

        }
        final var sortField = tmpSortField;
        final var reverseMul = tmpReverseMul;

        source.addRescorer(
            new CollapseRescorerBuilder(collapseExt.groupField())
                .windowSize(collapseExt.windowSize())
                .shardSize(collapseExt.shardSize())
                .setSorts(collapseExt.getSorts())
        );

        var collapseListener = new ActionListener<Response>() {
            @Override
            public void onResponse(Response response) {
                final var resp = (SearchResponse) response;
                final var searchHits = resp.getHits();
                final var hits = searchHits.getHits();
                if (hits.length == 0) {
                    listener.onResponse(response);
                    return;
                }

                final var collapsedHits = new ArrayList<SearchHit>(hits.length);

                if (sortField == null) {
                    final var seenGroupValues = new HashSet<>();
                    for (var hit : hits) {
                        final var groupDocField = hit.field(groupField);
                        if (groupDocField != null) {
                            final var groupValue = groupDocField.getValue();
                            if (groupValue != null) {
                                if (seenGroupValues.add(groupValue)) {
                                    collapsedHits.add(hit);
                                }
                                continue;
                            }
                        }
                        collapsedHits.add(hit);
                    }
                } else {
                    final var topGroups = new HashMap<Object, TopGroup>();

                    // TODO: can we instantiate FieldComparator
                    //  based on the type of the first sort value?
                    //
                    // final var firstSortValue = Arrays.stream(hits)
                    //     .map(h -> h.field(sortField).getValue())
                    //     .filter(Objects::nonNull)
                    //     .findFirst();

                    for (var hit : hits) {
                        final var groupDocField = hit.field(groupField);
                        if (groupDocField == null) {
                            collapsedHits.add(hit);
                            continue;
                        }

                        final var groupValue = groupDocField.getValue();
                        if (groupValue == null) {
                            collapsedHits.add(hit);
                            continue;
                        }

                        final var topGroup = topGroups.get(groupValue);
                        if (topGroup == null) {
                            collapsedHits.add(hit);
                            topGroups.put(
                                groupValue,
                                new TopGroup(
                                    collapsedHits.size() - 1,
                                    hit.field(sortField).getValue(),
                                    hit.getScore()
                                )
                            );
                            continue;
                        }

                        final var sortGroupField = hit.field(sortField);
                        final var sortValue = sortGroupField.getValue();
                        if (reverseMul * ANY_COMPARATOR.compare(topGroup.sortValue, sortValue) > 0) {
                            hit.score(topGroup.score);
                            collapsedHits.set(topGroup.collapsedIx, hit);
                            topGroup.sortValue = sortValue;
                        }
                    }
                }

                var from = origFrom;
                if (from <= 0) {
                    from = 0;
                }
                var fromIndex = Math.min(from, collapsedHits.size());
                var size = origSize;
                if (size <= 0) {
                    size = 10;
                }
                var toIndex = Math.min(fromIndex + size, collapsedHits.size());
                final var pagedCollapsedHits = collapsedHits
                    .subList(fromIndex, toIndex)
                    .toArray(new SearchHit[0]);
                final var collapsedSearchHits = new SearchHits(
                    pagedCollapsedHits, collapsedHits.size(), searchHits.getMaxScore()
                );

                final var internalResponse = new InternalSearchResponse(
                    collapsedSearchHits,
                    (InternalAggregations) resp.getAggregations(),
                    resp.getSuggest(),
                    new SearchProfileShardResults(resp.getProfileResults()),
                    resp.isTimedOut(),
                    resp.isTerminatedEarly(),
                    resp.getNumReducePhases()
                );
                @SuppressWarnings("unchecked") final var newResponse = (Response) new SearchResponse(
                    internalResponse,
                    resp.getScrollId(),
                    resp.getTotalShards(),
                    resp.getSuccessfulShards(),
                    resp.getSkippedShards(),
                    resp.getTook().millis(),
                    resp.getShardFailures(),
                    resp.getClusters()
                );

                listener.onResponse(newResponse);
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        };

        chain.proceed(task, action, request, collapseListener);
    }
}
