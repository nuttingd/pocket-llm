# Capability Ladder: M2 Federated Phase

**Milestone**: M2 - Privacy-Aware Learning Platform  
**Target**: 6 months from baseline (3 months after M1)  
**North Star Reference**: See ../north-star.md

---

## Theme

**Privacy-Aware Learning Platform**

The platform becomes smarter for each user while preserving complete privacy.

---

## Vision Statement

In M2, users will:

> The more I use Pocket LLM, the better it gets - not because it's learning on a cloud server, but because my device is optimizing for MY usage patterns. Other users benefit from my anonymized insights without anyone seeing what I actually said.

This is where privacy becomes a competitive advantage - we can learn from data that we never see.

---

## Federated Learning Architecture

### How It Works (Privacy-Preserving)

```
User Device                    Federated Server             Community
    |                                |                           |
    +-- Collects anonymous stats ----> (encrypted)                |
    |                                |                           |
    |                                +-- Aggregates patterns ---->|
    |                                |    (no raw data)          |
    |                                |                           v
    |<--- Distributed model update <--- Optimized weights --------+
    |
    +-- Applies improvements locally
```

### Data That's Collected (Opt-In Only)

| Data Type | What | Privacy Protection |
|-----------|------|-------------------|
| Usage Patterns | Which tools used together, common parameters | Federated aggregation only |
| Model Performance | Response latency, success rate | Aggregated stats per device class |
| Feature Adoption | Which features users enable | Count-only, no user IDs |

### Data That's NEVER Collected

- Conversation content
- API key usage patterns
- Document content (even uploaded)
- User's search queries
- Personal information

---

## Capability Ladder

### Tier 1: Federated Learning Client (Foundation)

| Feature | Description | Priority |
|---------|-------------|----------|
| Opt-In Analytics | User can enable anonymized stats collection | P0 |
| Encrypted Gradients | Send only aggregated gradients, not raw data | P0 |
| Model Distribution | Receive improved models from federation | P1 |

**Acceptance Criteria**:
- User opt-in rate > 70% after clear explanation
- No raw user data leaves device (verified by audit)
- Federated model update improves response quality measurable

**Technical Implementation**:
- Use encrypted transport (TLS with certificate pinning)
- Implement differential privacy on client side
- Aggregate using secure multi-party computation if possible

---

### Tier 2: Cross-Device Continuity

| Feature | Description | Priority |
|---------|-------------|----------|
| System Backup Sync | Use Android/iOS backup APIs | P0 |
| Nearby Connection | Device-to-device transfer when nearby | P1 |
| Conversation State Sync | Resume conversation on another device | P0 |

**Acceptance Criteria**:
- Conversation state sync works in < 10 seconds
- No user configuration required for sync
- All sync is end-to-end encrypted

**Technical Implementation**:
- Use Android Auto Backup for Apps API
- Implement Wi-Fi Direct or Nearby Connections API for direct sync
- Encrypt conversation data with per-device keys

---

### Tier 3: Advanced RAG

| Feature | Description | Priority |
|---------|-------------|----------|
| Document Chunking | Smart document splitting with overlap | P0 |
| Embedding Compression | Smaller embeddings without quality loss | P1 |
| Hybrid Search | Keyword + semantic fusion | P0 |

**Acceptance Criteria**:
- RAG recall rate > 80% on standard test corpus
- Embedding size < 1KB per document chunk
- Hybrid search beats either method alone by > 15%

**Technical Implementation**:
- Use sentence-transformers with quantization
- Implement BM25 + semantic fusion (RRF)
- Store compressed embeddings using FP16 or INT8

---

## Integration Points

### With M0 & M1 Primitives

| M2 Feature | Connects To | How |
|------------|-------------|-----|
| Federated Learning | Model Lifecycle | New model downloaded via same mechanism |
| Cross-Device Sync | Conversation Tree | Sync entire message tree |
| Advanced RAG | Document RAG (M1) | Enhances search with semantic |

### New Entities

**Room Schema Additions**:

```kotlin
@Entity(tableName = "federated_stats")
data class FederatedStat(
    @PrimaryKey val id: String,
    val statType: String,  // e.g., "tool_cooccurrence"
    val statKey: String,   // e.g., "calculator+web_fetch"
    val count: Int,
    val deviceIdHash: String,  // Anonymized
    val timestamp: Long
)

@Entity(tableName = "synced_conversations")
data class SyncedConversation(
    @PrimaryKey val conversationId: String,
    val lastSyncDate: Long,
    val syncStatus: String,  // synced, pending, failed
    val devicesSynced: String  // Comma-separated device IDs
)
```

---

## Quality Gates

### M2 Success Metrics

| Metric | Target |
|--------|--------|
| Federated opt-in rate | > 70% of users |
| Cross-device sync latency | < 10 seconds |
| RAG recall rate | > 80% |
| Model improvement (user-rated) | > 20% better |
| Privacy audit pass | 100% (no data leaks) |

---

## Anti-Patterns to Avoid

### 1. Feature Creep into Federated System

**Risk**: Adding too many data points to federation

**Prevention**: Strict scope control - only collect what improves model for ALL users.

### 2. Security Over-Engineering

**Risk**: Building complex crypto when simple TLS suffices

**Prevention**: Use battle-tested libraries (Tink), standard protocols.

### 3. Privacy theater

**Risk**: Claiming privacy while collecting identifiable data

**Prevention**: Independent security audit before launch.

---

## Milestone Exit Criteria

M2 is complete when:

- [ ] Federated learning works with > 70% opt-in
- [ ] No raw user data ever leaves device (verified by audit)
- [ ] Cross-device sync works zero-config
- [ ] RAG recall rate > 80%
- [ ] Federated model update measurable improvement
- [ ] Security audit passed

---

*Privacy isn't just compliance - it's a feature that users love and competitors can't easily match.*

## Next Milestone

**M3: Platform Phase** - Users treat Pocket LLM as their primary AI interface with a complete ecosystem.
