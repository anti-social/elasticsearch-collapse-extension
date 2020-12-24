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

import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.Rescorer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

public class CollapseRescorer implements Rescorer {
    // private static final org.apache.logging.log4j.Logger LOGGER =
    //     org.apache.logging.log4j.LogManager.getLogger(CollapseRescorer.class);

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
        final int shardSize;
        final Sort sort;

        Context(int windowSize, IndexFieldData<?> groupField, int shardSize, Sort sort) {
            super(windowSize, INSTANCE);
            this.groupField = groupField;
            this.shardSize = shardSize;
            this.sort = sort;
        }
    }

    static class CollapsedScoreDoc extends FieldDoc {
        int slot;
        final int collapseIx;

        CollapsedScoreDoc(ScoreDoc hit, int slot, Object[] fields, int collapseIx) {
            super(hit.doc, hit.score, fields, hit.shardIndex);
            this.slot = slot;
            this.collapseIx = collapseIx;
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ENGLISH,
                "GroupedScoreDoc<doc = %s, score = %s, slot = %s, ix = %s>",
                doc, score, slot, collapseIx
            );
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

        final var readerContexts = searcher.getIndexReader().leaves();
        var currentReaderIx = -1;
        var currentReaderEndDoc = 0;
        var currentReaderContext = readerContexts.get(0);
        SortedBinaryDocValues groupValues = ctx.groupField
            .load(currentReaderContext)
            .getBytesValues();

        final var sortFields = ctx.sort.getSort();
        final var sortField = sortFields[0];
        final var reverseMul = sortField.getReverse() ? -1 : 1;
        final var comparator = sortField.getComparator(hits.length, 0);
        final var leafComparator = comparator.getLeafComparator(currentReaderContext);
        final var docScorer = new Scorer(null) {
            private int doc;
            private float score;

            public void setDoc(int doc) {
                this.doc = doc;
            }

            public void setScore(float score) {
                this.score = score;
            }

            @Override
            public int docID() {
                return doc;
            }

            @Override
            public float score() {
                return score;
            }

            @Override
            public DocIdSetIterator iterator() {
                return null;
            }
        };
        leafComparator.setScorer(docScorer);

        final var collapsedHits = new ArrayList<CollapsedScoreDoc>(size);

        // Stores the most relevant hit data for every group
        final var groupTops = new HashMap<BytesRef, CollapsedScoreDoc>();

        var slot = -1;
        for (var hit : hits) {
            slot++;

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

            docScorer.setDoc(docId);
            docScorer.setScore(hit.score);
            leafComparator.copy(slot, docId);
            final var sortValue = comparator.value(slot);

            if (groupValues.advanceExact(docId)) {
                final var groupValue = groupValues.nextValue();
                final var top = groupTops.get(groupValue);

                if (top == null) {
                    final var scoreDoc = new CollapsedScoreDoc(
                        hit, slot, new Object[]{sortValue}, collapsedHits.size()
                    );
                    collapsedHits.add(scoreDoc);
                    groupTops.put(
                        BytesRef.deepCopyOf(groupValue), scoreDoc
                    );
                    continue;
                }

                // Use comparator's bottom as a top inverting condition
                leafComparator.setBottom(top.collapseIx);
                if (reverseMul * leafComparator.compareBottom(hit.doc) > 0) {
                    top.doc = hit.doc;
                    top.slot = slot;
                }
                if (hit.score > top.score) {
                    top.score = hit.score;
                }
            } else {
                final var scoreDoc = new CollapsedScoreDoc(
                    hit, slot, new Object[]{sortValue}, collapsedHits.size()
                );
                collapsedHits.add(scoreDoc);
            }
        }

        collapsedHits.sort(SCORE_DOC_COMPARATOR);

        final var numHits = Math.min(collapsedHits.size(), ctx.shardSize);
        final var trimmedHits = collapsedHits.stream()
            .limit(numHits)
            .map(doc -> new ScoreDoc(doc.doc, doc.score, doc.shardIndex))
            .toArray(ScoreDoc[]::new);
        return new TopDocs(
            topDocs.totalHits, trimmedHits, topDocs.getMaxScore()
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
        // TODO: explain that score of the collapsed hit could be borrowed
        return sourceExplanation;
    }
}
