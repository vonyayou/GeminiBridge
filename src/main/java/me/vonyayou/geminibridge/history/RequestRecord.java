package me.vonyayou.geminibridge.history;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "request_history", indexes = {@Index(name = "history_timestamp", columnList = "timestamp"), @Index(name = "history_model", columnList = "requestedModel")})
public class RequestRecord {
    @Id public String id;
    @Column(nullable = false) public Instant timestamp;
    public String requestedModel;
    public String resolvedModel;
    @Column(columnDefinition = "TEXT") public String requestHeaders;
    @Column(columnDefinition = "TEXT") public String requestBody;
    @Column(columnDefinition = "TEXT") public String responseBody;
    public long durationMs;
    public Integer inputTokens;
    public Integer outputTokens;
    public boolean streaming;
    public int httpStatus;
    @Column(length = 2048) public String errorMessage;
    public String client;
    public String provider;
    protected RequestRecord() {}
    public RequestRecord(String id, Instant timestamp) { this.id = id; this.timestamp = timestamp; }
}
