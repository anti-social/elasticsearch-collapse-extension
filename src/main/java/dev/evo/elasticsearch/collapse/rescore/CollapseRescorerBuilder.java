package dev.evo.elasticsearch.collapse.rescore;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.RescorerBuilder;

import java.io.IOException;

public class CollapseRescorerBuilder extends RescorerBuilder<CollapseRescorerBuilder> {
    public static final String NAME = "_collapse";

    private static final ParseField GROUPING_FIELD_FIELD = new ParseField("field");

    private static final ConstructingObjectParser<CollapseRescorerBuilder, Void> PARSER =
        new ConstructingObjectParser<>(
            NAME,
            args -> new CollapseRescorerBuilder((String) args[0])
        );
    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), GROUPING_FIELD_FIELD);
    }

    private final String groupField;

    public static CollapseRescorerBuilder fromXContent(XContentParser parser)
        throws ParsingException
    {
         return PARSER.apply(parser, null);
    }

    public CollapseRescorerBuilder(String groupField) {
        super();
        this.groupField = groupField;
    }

    public CollapseRescorerBuilder(StreamInput in) throws IOException {
        super(in);
        groupField = in.readString();
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
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(groupField);
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(GROUPING_FIELD_FIELD.getPreferredName(), groupField);
        builder.endObject();
    }

    @Override
    protected RescoreContext innerBuildContext(int windowSize, QueryShardContext context)  {
        final var groupFieldData = context.getForField(context.fieldMapper(groupField));
        return new CollapseRescorer.Context(windowSize, groupFieldData);
    }
}
