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
import org.elasticsearch.tasks.Task;

import java.util.ArrayList;
import java.util.HashSet;

public class CollapseRescoreFilter implements ActionFilter {
    public static Setting<Integer> COLLAPSE_RESCORE_FILTER_ORDER = Setting.intSetting(
        "collapse.rescore.filter.order", 10, Setting.Property.NodeScope
    );

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

        source.addRescorer(
            new CollapseRescorerBuilder(collapseExt.groupField())
                .windowSize(collapseExt.windowSize())
                .shardSize(collapseExt.shardSize())
        );

        var collapseListener = new ActionListener<Response>() {
            @Override
            public void onResponse(Response response) {
                final var resp = (SearchResponse) response;
                final var hits = resp.getHits();

                final var collapsedHits = new ArrayList<SearchHit>(hits.getHits().length);
                final var seenGroupValues = new HashSet<>();
                for (var hit : hits.getHits()) {
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
                final var searchHits = new SearchHits(
                    pagedCollapsedHits, collapsedHits.size(), hits.getMaxScore()
                );

                final var internalResponse = new InternalSearchResponse(
                    searchHits,
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
