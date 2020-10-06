package dev.evo.elasticsearch.collapse;

import dev.evo.elasticsearch.collapse.rescore.CollapseRescorerBuilder;

import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;

import java.util.List;

public class CollapseRescorePlugin extends Plugin implements ActionPlugin, SearchPlugin {
    private final Settings settings;

    public CollapseRescorePlugin(final Settings settings) {
        this.settings = settings;
    }

    @Override
    public List<ActionFilter> getActionFilters() {
        return List.of(new CollapseRescoreFilter(settings));
    }

    @Override
    public List<SearchExtSpec<?>> getSearchExts() {
        return List.of(
            new SearchExtSpec<>(
                CollapseSearchExtBuilder.NAME,
                CollapseSearchExtBuilder::new,
                CollapseSearchExtBuilder::fromXContent
            )
        );
    }

    @Override
    public List<RescorerSpec<?>> getRescorers() {
        return List.of(
            new RescorerSpec<>(
                CollapseRescorerBuilder.NAME,
                CollapseRescorerBuilder::new,
                CollapseRescorerBuilder::fromXContent
            )
        );
    }
}
