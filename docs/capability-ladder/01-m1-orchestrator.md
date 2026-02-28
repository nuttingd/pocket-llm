# Capability Ladder: M1 Orchestrator Phase

**Milestone**: M1 - Personal Knowledge Orchestrator  
**Target**: 3 months from baseline  
**North Star Reference**: See ../north-star.md

---

## Theme

**Personal Knowledge Orchestrator**

Users can maintain and query a personal knowledge base while delegating tasks to agents.

---

## Vision Statement

In M1, users will:

> I can upload documents, ask questions about them with context from past conversations, and delegate complex research tasks to custom agents that work autonomously - all on my device with zero data leaving it.

This is where "conversation" becomes "orchestration" - you're not just chatting, you're directing a personal AI team.

---

## Capability Ladder

### Tier 1: Document RAG Engine (Foundation)

**Goal**: Users can upload documents and search them semantically.

| Feature | Description | Priority |
|---------|-------------|----------|
| Document Upload | Accept PDF/text files via Android share sheet or file picker | P0 |
| Local Embedding | Generate embeddings on-device using lightweight model | P0 |
| Semantic Search | Search documents by meaning, not just keywords | P0 |
| Context-Aware Retrieval | Retrieve relevant document snippets for queries | P1 |

**Acceptance Criteria**:
- Document upload works for PDF and text files
- Search returns results in < 5 seconds on 1000+ document corpus
- Search quality (precision/recall) comparable to basic keyword search

**Technical Implementation**:
- Use DistilBERT or similar lightweight embedding model
- Store embeddings alongside documents in Room database
- Implement hybrid search: keyword + semantic fusion

---

### Tier 2: Agent Framework (Autonomy)

**Goal**: Users can create and execute custom agents.

| Feature | Description | Priority |
|---------|-------------|----------|
| Agent Definition | Create agents with name, description, tools | P0 |
| Tool Selection | Choose which tools each agent can use | P0 |
| Execution Queue | Agents execute tasks in order | P1 |
| Progress Tracking | User sees agent's progress on multi-step tasks | P1 |

**Acceptance Criteria**:
- Agent completes 3-step task without user intervention
- User can create custom agent in < 2 minutes
- Agent output is traceable (user knows what agent did)

**Technical Implementation**:
- Extend ChatManager to support agent contexts
- Add Agent entity to Room with tool preferences
- Implement agent execution state machine

---

### Tier 3: Context-Aware Tools (Intelligence)

**Goal**: Tools that understand conversation context.

| Feature | Description | Priority |
|---------|-------------|----------|
| Conversation Awareness | Tools can access recent conversation history | P0 |
| Auto-Suggested Actions | Suggest relevant actions based on conversation content | P1 |
| Context-Based Parameters | Automatically set tool parameters from context | P2 |

**Acceptance Criteria**:
- Calculator tool receives appropriate precision based on context
- Web fetch tool suggests relevant URLs based on discussion topic
- User can disable auto-suggestions

**Technical Implementation**:
- Add ConversationContext provider to ToolExecutor
- Implement lightweight pattern matching for suggestion engine
- Store user preferences for suggestion sensitivity

---

## Integration Points

### With M0 Primitives

| M1 Feature | Connects To | How |
|------------|-------------|-----|
| Document RAG | Model Lifecycle | Embedding model downloaded via same mechanism as LLM |
| Agent Framework | Stream State Machine | Agents produce same ChatCompletionChunk events |
| Context-Aware Tools | Parameter Space | Share parameter presets for tools |

### New Entities

**Room Schema Additions**:

```kotlin
@Entity(tableName = "documents")
data class Document(
    @PrimaryKey val id: String,
    val title: String,
    val contentText: String,  // Full text extracted from PDF
    val embeddingId: String,  // Reference to embeddings table
    val uploadDate: Long,
    val conversationLinkId: String?  // Optional link to conversation
)

@Entity(tableName = "document_embeddings")
data class DocumentEmbedding(
    @PrimaryKey val id: String,
    val documentId: String,
    val embeddingJson: String,  // JSON array of floats
    val chunkIndex: Int
)

@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolIds: String,  // Comma-separated list
    val defaultTemperature: Float,
    val createdAt: Long
)
```

---

## Quality Gates

### M1 Success Metrics

| Metric | Target |
|--------|--------|
| Document search latency (100 docs) | < 3 seconds |
| Agent task completion rate | > 80% |
| User retention after M1 release | > 65% |
| Accessibility pass rate | 100% |

---

## Anti-Patterns to Avoid

### 1. Cloud Dependency Creep

**Risk**: "Just upload embeddings to server for faster search"

**Prevention**: Document this as out-of-scope in spec. Require local-only embedding models.

### 2. Premature Agent Abstraction

**Risk**: Creating complex agent system before M0 is stable

**Prevention**: Agent framework must use existing ChatManager primitives. No new streaming interface.

### 3. Over-Engineering Embeddings

**Risk**: Building custom embedding model when pre-trained exists

**Prevention**: Use DistilBERT or similar community-standard lightweight model.

---

## Milestone Exit Criteria

M1 is complete when:

- [ ] Document upload works for PDF and text
- [ ] Semantic search on 1000 docs < 5 seconds
- [ ] User can create agent in < 2 minutes
- [ ] Agent completes 3-step task > 80% of time
- [ ] Context-aware tools reduce user input by > 30%
- [ ] All accessibility requirements pass
- [ ] North Star alignment reviewed (local-first, privacy)

---

*This is the first major evolution from chat client to personal platform.*

## Next Milestone

**M2: Federated Phase** - The platform becomes smarter for each user while preserving complete privacy.
