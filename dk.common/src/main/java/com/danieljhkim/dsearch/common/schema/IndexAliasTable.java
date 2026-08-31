package com.danieljhkim.dsearch.common.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexAliasTable {
    private Map<String, IndexAlias> aliases = new LinkedHashMap<>();
    private Map<String, ReindexJob> reindexJobs = new LinkedHashMap<>();

    public IndexAliasTable copy() {
        IndexAliasTable copy = new IndexAliasTable();
        aliases.forEach((name, alias) -> copy.aliases.put(name, alias.copy()));
        reindexJobs.forEach((id, job) -> {
            ReindexJob jobCopy = new ReindexJob();
            jobCopy.setJobId(job.getJobId());
            jobCopy.setSourceAlias(job.getSourceAlias());
            jobCopy.setSourceIndex(job.getSourceIndex());
            jobCopy.setTargetIndex(job.getTargetIndex());
            jobCopy.setStatus(job.getStatus());
            jobCopy.setSourceCount(job.getSourceCount());
            jobCopy.setTargetCount(job.getTargetCount());
            jobCopy.setVerificationPassed(job.isVerificationPassed());
            jobCopy.setError(job.getError());
            copy.reindexJobs.put(id, jobCopy);
        });
        return copy;
    }
}
