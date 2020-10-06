package dev.evo.elasticsearch.collapse.rescore;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.Rescorer;

import java.io.IOException;
import java.util.*;

public class CollapseRescorer implements Rescorer {
    private static final CollapseRescorer INSTANCE = new CollapseRescorer();

    private static final Comparator<ScoreDoc> DOC_COMPARATOR = Comparator.comparingInt(d -> d.doc);

    private static final Comparator<ScoreDoc> SCORE_DOC_COMPARATOR = (a, b) -> {
        if (a.score > b.score) {
            return -1;
        }
        else if (a.score < b.score) {
            return 1;
        }
        return a.doc - b.doc;
    };

    static class Context extends RescoreContext {
        final IndexFieldData<?> groupField;

        public Context(int windowSize, IndexFieldData<?> groupField) {
            super(windowSize, INSTANCE);
            this.groupField = groupField;
        }
    }

    @Override
    public TopDocs rescore(
        TopDocs topDocs, IndexSearcher searcher, RescoreContext rescoreContext
    ) throws IOException {
        final var ctx = (Context) rescoreContext;
        if (topDocs == null || topDocs.totalHits == 0 || topDocs.scoreDocs.length == 0) {
            return topDocs;
        }

        final var hits = topDocs.scoreDocs;

        final var size = Math.min(ctx.getWindowSize(), hits.length);
        if (size <= 0) {
            return topDocs;
        }

        Arrays.sort(hits, DOC_COMPARATOR);

        final var collapsedHits = new ArrayList<ScoreDoc>(size);

        final var readerContexts = searcher.getIndexReader().leaves();
        var currentReaderIx = -1;
        var currentReaderEndDoc = 0;
        var currentReaderContext = readerContexts.get(0);
        SortedBinaryDocValues groupValues = ctx.groupField
            .load(currentReaderContext)
            .getBytesValues();

        final var seenGroupValues = new HashMap<BytesRef, Float>();

        for (var hit : hits) {
            final var prevReaderContext = currentReaderContext;

            // find segment that contains current document
            while (hit.doc >= currentReaderEndDoc) {
                currentReaderIx++;
                currentReaderContext = readerContexts.get(currentReaderIx);
                currentReaderEndDoc = currentReaderContext.docBase +
                    currentReaderContext.reader().maxDoc();
            }

            final int docId = hit.doc - currentReaderContext.docBase;
            if (currentReaderContext != prevReaderContext) {
                groupValues = ctx.groupField
                    .load(currentReaderContext)
                    .getBytesValues();
            }
            if (groupValues.advanceExact(docId)) {
                final var groupValue = groupValues.nextValue();
                final var maxGroupScore = seenGroupValues.get(groupValue);
                if (maxGroupScore == null || hit.score > maxGroupScore) {
                    collapsedHits.add(hit);
                    seenGroupValues.put(groupValue.clone(), hit.score);
                }
            } else {
                collapsedHits.add(hit);
            }
        }

        collapsedHits.sort(SCORE_DOC_COMPARATOR);

        return new TopDocs(
            topDocs.totalHits,
            collapsedHits
                .subList(0, Math.min(size, collapsedHits.size()))
                .toArray(new ScoreDoc[0]),
            topDocs.getMaxScore()
        );
    }

    @Override
    public void extractTerms(
        IndexSearcher searcher, RescoreContext rescoreContext, Set<Term> termsSet
    ) {}

    @Override
    public Explanation explain(
        int topLevelDocId,
        IndexSearcher searcher,
        RescoreContext rescoreContext,
        Explanation sourceExplanation
    ) {
        return sourceExplanation;
    }
}
