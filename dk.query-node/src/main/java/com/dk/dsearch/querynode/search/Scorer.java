package com.dk.dsearch.querynode.search;

public class Scorer {
    private final double k1 = 1.5;
    private final double b = 0.75;

    public double score(int termFrequency, int docLength, int avgDocLength, int docFrequency, int totalDocs) {
        double idf = Math.log((totalDocs - docFrequency + 0.5) / (docFrequency + 0.5) + 1);
        double tf = (termFrequency * (k1 + 1)) / (termFrequency + k1 * (1 - b + b * docLength / avgDocLength));
        return idf * tf;
    }
}