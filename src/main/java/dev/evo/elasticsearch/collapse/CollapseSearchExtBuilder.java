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

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.SearchExtBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CollapseSearchExtBuilder extends SearchExtBuilder {
    public static final String NAME = "collapse";

    private static final ParseField GROUP_FIELD_NAME = new ParseField("field");

    // Window size on which we will operate to group and collapse documents
    private static final ParseField WINDOW_SIZE_FIELD_NAME = new ParseField("window_size");
    private static final int DEFAULT_WINDOW_SIZE = 10_000;

    // Number of documents that will be returned from a shard
    private static final ParseField SHARD_SIZE_FIELD_NAME = new ParseField("shard_size");
    private static final int DEFAULT_SHARD_SIZE = 1_000;

    private static final ParseField PAGINATION_FIELD_NAME = new ParseField("pagination");
    private static final boolean DEFAULT_PAGINATION = true;

    private static final ConstructingObjectParser<CollapseSearchExtBuilder, Void> PARSER =
        new ConstructingObjectParser<>(
            NAME,
            args -> new CollapseSearchExtBuilder((String) args[0])
        );
    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), GROUP_FIELD_NAME);
        PARSER.declareInt(CollapseSearchExtBuilder::windowSize, WINDOW_SIZE_FIELD_NAME);
        PARSER.declareInt(CollapseSearchExtBuilder::shardSize, SHARD_SIZE_FIELD_NAME);
        PARSER.declareBoolean(CollapseSearchExtBuilder::pagination, PAGINATION_FIELD_NAME);
        PARSER.declareField(
            CollapseSearchExtBuilder::setSorts,
            (parser, ctx) -> checkSorts(SortBuilder.fromXContent(parser)),
            SearchSourceBuilder.SORT_FIELD,
            ObjectParser.ValueType.OBJECT_ARRAY
        );
    }

    private final String groupField;
    private int windowSize = DEFAULT_WINDOW_SIZE;
    private int shardSize = DEFAULT_SHARD_SIZE;
    private boolean pagination = DEFAULT_PAGINATION;
    private List<SortBuilder<?>> sorts;

    public CollapseSearchExtBuilder(String groupField) {
        this.groupField = groupField;
        this.sorts = new ArrayList<>();
    }

    public CollapseSearchExtBuilder(StreamInput in) throws IOException {
        groupField = in.readString();
        windowSize = in.readInt();
        shardSize = in.readInt();
        pagination = in.readBoolean();
        final int size = in.readVInt();
        sorts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sorts.add(in.readNamedWriteable(SortBuilder.class));
        }
        checkSorts(sorts);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(groupField);
        out.writeInt(windowSize);
        out.writeInt(shardSize);
        out.writeBoolean(pagination);
        out.writeVInt(sorts.size());
        for (var sort : sorts) {
            out.writeNamedWriteable(sort);
        }
    }

    private static List<SortBuilder<?>> checkSorts(List<SortBuilder<?>> sorts) {
        if (sorts.size() > 1) {
            throw new IllegalArgumentException("Currently only single sort is supported");
        }
        for (var sort : sorts) {
            if (sort instanceof FieldSortBuilder) {
                continue;
            }
            if (sort instanceof ScriptSortBuilder) {
                continue;
            }
            throw new IllegalArgumentException("Only field and script sort are supported");
        }
        return sorts;
    }

    public static CollapseSearchExtBuilder fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    public String groupField() {
        return groupField;
    }

    public CollapseSearchExtBuilder windowSize(int size) {
        this.windowSize = size;
        return this;
    }

    public int windowSize() {
        return windowSize;
    }

    public CollapseSearchExtBuilder shardSize(int shardSize) {
        this.shardSize = shardSize;
        return this;
    }

    public int shardSize() {
        return shardSize;
    }

    public CollapseSearchExtBuilder pagination(boolean pagination) {
        this.pagination = pagination;
        return this;
    }

    public boolean pagination() {
        return pagination;
    }

    public List<SortBuilder<?>> getSorts() {
        return sorts;
    }

    public CollapseSearchExtBuilder setSorts(List<SortBuilder<?>> sorts) {
        this.sorts = sorts;
        return this;
    }

    public CollapseSearchExtBuilder addSort(SortBuilder<?> sort) {
        sorts.add(sort);
        return this;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(GROUP_FIELD_NAME.getPreferredName(), groupField);
        builder.field(WINDOW_SIZE_FIELD_NAME.getPreferredName(), windowSize);
        builder.field(SHARD_SIZE_FIELD_NAME.getPreferredName(), shardSize);
        builder.endObject();
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupField, windowSize, shardSize);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CollapseSearchExtBuilder)) {
            return false;
        }
        var other = (CollapseSearchExtBuilder) obj;
        return other.groupField.equals(groupField) &&
            other.windowSize == windowSize &&
            other.shardSize == shardSize;
    }
}
