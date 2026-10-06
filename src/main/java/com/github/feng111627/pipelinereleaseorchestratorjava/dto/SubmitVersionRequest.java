package com.github.feng111627.pipelinereleaseorchestratorjava.dto;

public class SubmitVersionRequest {
    private String version;
    private String commitRef;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getCommitRef() { return commitRef; }
    public void setCommitRef(String commitRef) { this.commitRef = commitRef; }
}
