package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReindexJob {
    public static final String STATUS_COPYING = "copying";
    public static final String STATUS_VERIFIED = "verified";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_INTERRUPTED = "interrupted";

    private String jobId;
    private String sourceAlias;
    private String sourceIndex;
    private String targetIndex;
    private String status;
    private long sourceCount;
    private long targetCount;
    private boolean verificationPassed;
    private String error;

    public ReindexJob() {}

    public boolean isComplete() {
        return STATUS_VERIFIED.equals(status);
    }

    public boolean isActiveSource() {
        return !STATUS_VERIFIED.equals(status);
    }
}
