package dev.evo.elasticsearch.collapse;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.SearchExtBuilder;

import java.io.IOException;
import java.util.Objects;

public class CollapseSearchExtBuilder extends SearchExtBuilder {
    public static final String NAME = "collapse";

    private static final ParseField GROUP_FIELD_NAME = new ParseField("field");
    private static final ParseField SIZE_FIELD_NAME = new ParseField("size");
    private static final ParseField SHARD_SIZE_FIELD_NAME = new ParseField("shard_size");

    private static final ConstructingObjectParser<CollapseSearchExtBuilder, Void> PARSER =
        new ConstructingObjectParser<>(
            NAME,
            args -> new CollapseSearchExtBuilder((String) args[0])
        );
    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), GROUP_FIELD_NAME);
        PARSER.declareInt(CollapseSearchExtBuilder::size, SIZE_FIELD_NAME);
        PARSER.declareInt(CollapseSearchExtBuilder::shardSize, SHARD_SIZE_FIELD_NAME);
    }

    private final String groupField;
    private int size = 1000;
    private int shardSize = 10_000;

    public CollapseSearchExtBuilder(String groupField) {
        this.groupField = groupField;
    }

    public CollapseSearchExtBuilder(StreamInput in) throws IOException {
        groupField = in.readString();
        size = in.readInt();
        shardSize = in.readInt();
    }

    public static CollapseSearchExtBuilder fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    public String groupField() {
        return groupField;
    }

    public void size(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public void shardSize(int shardSize) {
        this.shardSize = shardSize;
    }

    public int shardSize() {
        return shardSize;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(groupField);
        out.writeInt(size);
        out.writeInt(shardSize);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(GROUP_FIELD_NAME.getPreferredName(), groupField);
        builder.field(SIZE_FIELD_NAME.getPreferredName(), size);
        builder.field(SHARD_SIZE_FIELD_NAME.getPreferredName(), shardSize);
        builder.endObject();
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupField, size, shardSize);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CollapseSearchExtBuilder)) {
            return false;
        }
        var other = (CollapseSearchExtBuilder) obj;
        return other.groupField.equals(groupField) &&
            other.size == size &&
            other.shardSize == shardSize;
    }
}
