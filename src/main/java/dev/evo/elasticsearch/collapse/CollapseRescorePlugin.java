/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
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
