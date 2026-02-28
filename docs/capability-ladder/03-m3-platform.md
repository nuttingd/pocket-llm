# Capability Ladder: M3 Platform Phase

**Milestone**: M3 - Open Agent Ecosystem  
**Target**: 12 months from baseline (6 months after M2)  
**North Star Reference**: See ../north-star.md

---

## Theme

**Open Agent Ecosystem**

Users treat Pocket LLM as their primary AI interface with a complete ecosystem.

---

## Vision Statement

In M3, users will:

> I have an entire team of specialized agents working on different tasks for me - researching, writing, analyzing. They communicate with each other when needed. My phone handles the workload intelligently, balancing battery and performance. When I share my workflow, others can import it immediately.

This is where Pocket LLM becomes an operating system - not just an app.

---

## Platform Architecture

### Agent Network Topology

```
User (Human)
    │
    ├── ResearchAgent (web search, document analysis)
    │   └── Communicates with → WritingAgent
    │
    ├── WritingAgent (drafting, editing, formatting)
    │   ├── Communicates with → ResearchAgent
    │   └── Communicates with → CodeAgent
    │
    ├── CodeAgent (code generation, debugging, refactoring)
    │   └── Communicates with → WritingAgent
    │
    └── CoordinatorAgent (task scheduling, resource management)
        └── Coordinates all above agents
```

### Resource-Aware Scheduling

| Device State | Behavior |
|--------------|----------|
| Charging + Plugged in | Max performance mode, parallel agent execution |
| Battery < 20% | Single agent at a time, aggressive offloading |
| High CPU temperature | Reduce agent count, lower model precision |
| Low memory | Unload inactive agents' models |

---

## Capability Ladder

### Tier 1: Agent Network (Foundation)

| Feature | Description | Priority |
|---------|-------------|----------|
| Multiple Agents | Run multiple agents concurrently | P0 |
| Agent Communication | Agents exchange structured messages | P0 |
| Resource Coordinator | Balance agents against device constraints | P1 |

**Acceptance Criteria**:
- 3+ agents can run simultaneously without OOM
- Agent-to-agent communication < 2 seconds latency
- Device stays responsive during agent execution

**Technical Implementation**:
- Extend ChatManager to handle multiple concurrent streams
- Implement structured message format for agent communication
- Add battery/CPU/memory monitoring with adaptive scheduling

---

### Tier 2: Community Ecosystem

| Feature | Description | Priority |
|---------|-------------|----------|
| Shared Tool Library | Community-maintained tools | P0 |
| Exportable Workflows | Save/load complete agent setups | P0 |
| Signed Artifacts | Verify tool/authenticity | P1 |

**Acceptance Criteria**:
- User can export workflow and import on another device
- 100+ community tools available at launch
- All imported tools cryptographically verified

**Technical Implementation**:
- Build simple REST API for tool distribution
- Implement GPG signature verification for tools
- Create UI for browsing/importing community tools

---

### Tier 3: Offline Workstation

| Feature | Description | Priority |
|---------|-------------|----------|
| Predictive Loading | Pre-load likely needed models | P0 |
| Smart Swapping | Swap models based on predicted use | P1 |
| Focus Mode | Optimize for distraction-free work | P2 |

**Acceptance Criteria**:
- 80% of user's common tasks have model pre-loaded
- Model swap latency < 3 seconds (vs. current 5s baseline)
- Focus mode extends battery by > 30%

**Technical Implementation**:
- Learn usage patterns from user's calendar and past activity
- Implement LRU-based model eviction
- Add "Do Not Disturb for AI" mode

---

## Integration Points

### With All Previous Phases

| M3 Feature | Connects To | How |
|------------|-------------|-----|
| Agent Network | Stream State Machine (M0) | Each agent produces same chunk events |
| Federated Learning (M2) | Model updates improve all agents |
| Cross-Device Sync (M2) | Resume agents across devices |
| RAG (M1/M2) | Agents use document search as a tool |

### New Entities

**Room Schema Additions**:

```kotlin
@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolIds: String,  // Comma-separated
    val defaultTemperature: Float,
    val isActive: Boolean,
    val resourceBudget: Int,  // Estimated MB RAM needed
    val createdAt: Long
)

@Entity(tableName = "agent_communications")
data class AgentCommunication(
    @PrimaryKey val id: String,
    val senderAgentId: String,
    val receiverAgentId: String,
    val messageContent: String,
    val timestamp: Long
)
```

---

## Quality Gates

### M3 Success Metrics

| Metric | Target |
|--------|--------|
| Concurrent agents (stable) | 4+ agents |
| Agent task completion rate | > 80% |
| Battery impact vs. messaging app | < 5% more per day |
| Import/export workflow time | < 1 minute |
| User retention after M3 release | > 70% |

---

## Anti-Patterns to Avoid

### 1. Agent Complexity Creep

**Risk**: Each agent trying to do everything

**Prevention**: Strict single-responsibility principle for agents.

### 2. Over-Optimization at Cost of Simplicity

**Risk**: Complex scheduling algorithm causes bugs

**Prevention**: Start with simple heuristics, optimize after real-world data.

### 3. Ecosystem Centralization

**Risk**: Building central tool store that could be censored

**Prevention**: Community-maintained, distributed distribution system.

---

## Milestone Exit Criteria

M3 is complete when:

- [ ] 4+ agents run simultaneously without crashes
- [ ] Agent-to-agent communication < 2 seconds
- [ ] User can export/import workflows in < 1 minute
- [ ] Battery impact measurable and acceptable
- [ ] Predictive loading reduces wait time > 50%
- [ ] Focus mode extends battery by > 30%
- [ ] Community tool library has 100+ tools

---

*This is the culmination of 12 months of work - an operating system for personal intelligence.*

## North Star Alignment Check

| Principle | M3 Alignment |
|-----------|-------------|
| Local-First | ✅ All agents run on-device |
| Privacy | ✅ No data leaves device, even agent communication |
| Open Architecture | ✅ Community tools, exportable workflows |
| Resource Respect | ✅ Adaptive scheduling based on device state |

---

*When complete, users won't just use Pocket LLM - they'll depend on it as their primary AI interface.*

## Post-M3 Vision

**V5+**: The platform evolves into:

- **Collaborative Agents**: Users can share agent workflows with friends/team
- **Continuous Learning**: Models adapt in real-time based on feedback
- **Voice Interface**: Natural conversation via voice input/output
- **Desktop Extension**: Full desktop app for power users

The foundation is solid. Now it grows.
