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
package dev.evo.elasticsearch.collapse.rescore;

import org.apache.lucene.search.Sort;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.RescorerBuilder;
import org.elasticsearch.search.sort.SortBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CollapseRescorerBuilder extends RescorerBuilder<CollapseRescorerBuilder> {
    public static final String NAME = "_collapse";

    private static final ParseField GROUPING_FIELD = new ParseField("field");
    private static final ParseField SHARD_SIZE_FIELD = new ParseField("shard_size");

    private static final ConstructingObjectParser<CollapseRescorerBuilder, Void> PARSER =
        new ConstructingObjectParser<>(
            NAME,
            args -> new CollapseRescorerBuilder((String) args[0])
        );
    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), GROUPING_FIELD);
        PARSER.declareInt(CollapseRescorerBuilder::shardSize, SHARD_SIZE_FIELD);
        PARSER.declareField(
            CollapseRescorerBuilder::setSorts,
            (parser, ctx) -> SortBuilder.fromXContent(parser),
            SearchSourceBuilder.SORT_FIELD,
            ObjectParser.ValueType.OBJECT_ARRAY
        );
    }

    private final String groupField;
    private int shardSize = -1;
    private List<SortBuilder<?>> sorts;

    public static CollapseRescorerBuilder fromXContent(XContentParser parser)
        throws ParsingException
    {
         return PARSER.apply(parser, null);
    }

    public CollapseRescorerBuilder(String groupField) {
        super();
        this.groupField = groupField;
        this.sorts = new ArrayList<>();
    }

    public CollapseRescorerBuilder(StreamInput in) throws IOException {
        super(in);
        groupField = in.readString();
        shardSize = in.readInt();
        final int size = in.readVInt();
        sorts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sorts.add(in.readNamedWriteable(SortBuilder.class));
        }
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(groupField);
        out.writeInt(shardSize);
        out.writeVInt(sorts.size());
        for (var sort : sorts) {
            out.writeNamedWriteable(sort);
        }
    }

    public int shardSize() {
        return shardSize;
    }

    public CollapseRescorerBuilder shardSize(int shardSize) {
        this.shardSize = shardSize;
        return this;
    }

    public CollapseRescorerBuilder setSorts(List<SortBuilder<?>> sorts) {
        this.sorts = sorts;
        return this;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public RescorerBuilder<CollapseRescorerBuilder> rewrite(QueryRewriteContext ctx) {
        return this;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(GROUPING_FIELD.getPreferredName(), groupField);
        builder.endObject();
    }

    @Override
    protected RescoreContext innerBuildContext(
        int windowSize, QueryShardContext context
    ) throws IOException {
        final var groupFieldType = context.fieldMapper(groupField);
        if (groupFieldType == null) {
            throw new QueryShardException(
                context, "no mapping found for `" + groupField + "` in order to collapse on"
            );
        }
        final var groupFieldData = context.getForField(groupFieldType);
        var shardSize = this.shardSize < 0 ? windowSize : this.shardSize;
        var sort = SortBuilder.buildSort(sorts, context)
            .map(s -> s.sort)
            .orElse(Sort.RELEVANCE);
        return new CollapseRescorer.Context(
            windowSize, groupFieldData, shardSize, sort
        );
    }
}
