# BinomialHash JVM

[![CI](https://github.com/Binomial-Capital-Management/binomialhash-jvm/actions/workflows/ci.yml/badge.svg)](https://github.com/Binomial-Capital-Management/binomialhash-jvm/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.binomial-capital-management/binomialhash)](https://central.sonatype.com/artifact/io.github.binomial-capital-management/binomialhash)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Content-addressed, schema-aware structured data compaction for LLM tool outputs — Java/JVM edition.

BinomialHash intercepts large JSON payloads from tool calls, infers schema and statistics, deduplicates by content fingerprint, and returns compact summaries that fit in LLM context windows. Agent tools let the model retrieve, aggregate, query, group, and export data on demand without blowing the token budget.

> Looking for the Python package? See [binomialhash](https://github.com/Binomial-Capital-Management/binomialhash).

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.binomial-capital-management:binomialhash:0.1.2")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.binomial-capital-management:binomialhash:0.1.2'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.binomial-capital-management</groupId>
    <artifactId>binomialhash</artifactId>
    <version>0.1.2</version>
</dependency>
```

### Optional Dependencies

| Dependency | Purpose |
|---|---|
| `org.apache.poi:poi-ooxml:5.2.5` | Excel export |
| `com.knuddels:jtokkit:1.1.0` | Exact OpenAI token counting |

## Quickstart

```java
import io.github.binomial-capital-management.binomialhash.BinomialHash;
import com.fasterxml.jackson.databind.ObjectMapper;

var bh = new BinomialHash();
var mapper = new ObjectMapper();

// Ingest a large JSON payload
String raw = mapper.writeValueAsString(List.of(
    Map.of("ticker", "AAPL", "price", 189.50, "volume", 54_000_000, "sector", "Technology"),
    Map.of("ticker", "MSFT", "price", 378.20, "volume", 28_000_000, "sector", "Technology"),
    Map.of("ticker", "JPM",  "price", 195.30, "volume", 12_000_000, "sector", "Financials")
    // ... hundreds more rows ...
));

String summary = bh.ingest(raw, "market_data");
// If raw > 3000 chars: compact schema + stats summary
// If small: passes through unchanged

// Query stored data
Object rows = bh.retrieve("market_data_abc123", 0, 10, null, true, null);
Object agg  = bh.aggregate("market_data_abc123", "price", "mean");
```

## Provider Adapters

BinomialHash ships with provider-neutral `ToolSpec` definitions that expose its full API to any LLM. Adapters translate these into provider-specific wire formats.

### OpenAI

```java
import io.github.binomial-capital-management.binomialhash.adapters.OpenAiAdapter;
import io.github.binomial-capital-management.binomialhash.tools.ToolSpec;

List<ToolSpec> specs = buildMySpecs(bh);
var tools = OpenAiAdapter.getOpenAiTools(specs);           // Responses API format
var tools = OpenAiAdapter.getOpenAiTools(specs, false, "chat_completions"); // Legacy

// Handle function calls
Object result = OpenAiAdapter.handleOpenAiToolCall(specs, name, arguments);
```

### Anthropic

```java
import io.github.binomial-capital-management.binomialhash.adapters.AnthropicAdapter;

var tools = AnthropicAdapter.getAnthropicTools(specs);
Object result = AnthropicAdapter.handleAnthropicToolUse(specs, name, inputMap);
```

### Google Gemini

```java
import io.github.binomial-capital-management.binomialhash.adapters.GeminiAdapter;

var tools = GeminiAdapter.getGeminiTools(specs);
Object result = GeminiAdapter.handleGeminiToolCall(specs, name, argsMap);
```

### xAI / Grok

```java
import io.github.binomial-capital-management.binomialhash.adapters.XaiAdapter;

var tools = XaiAdapter.getXaiTools(specs);  // Uses Responses API format
Object result = XaiAdapter.handleXaiToolCall(specs, name, arguments);
```

### Provider Router

```java
import io.github.binomial-capital-management.binomialhash.adapters.AdapterRouter;

var tools = AdapterRouter.getToolsForProvider(specs, "openai");
var tools = AdapterRouter.getToolsForProvider(specs, "anthropic");
var tools = AdapterRouter.getToolsForProvider(specs, "gemini");
var tools = AdapterRouter.getToolsForProvider(specs, "xai");
```

## Context Stats

```java
Map<String, Object> stats = bh.contextStats();
// {tool_calls=5, chars_in_raw=120000, chars_out_to_llm=8000,
//  compression_ratio=15.0, est_tokens_out=2000, ...}
```

## Examples

The `examples` package contains 17 runnable examples covering:

| # | Example | Description |
|---|---|---|
| 01 | OpenAI Agent Loop | Multi-turn tool-use with GPT |
| 02 | Anthropic Tool Use | Claude tool-use loop |
| 03 | Gemini Function Calling | Google Gemini integration |
| 04 | ToolSpec Bridge | Schema → multi-provider adapters |
| 05 | Streaming Demo | Token-by-token output simulation |
| 07 | Multi-Agent Handoff | Agent-to-agent delegation |
| 08 | Middleware Demo | Auto-interception of tool outputs |
| 09 | Multi-Tenant Isolation | ThreadLocal-based data isolation |
| 10 | Financial Analysis | Sector grouping, regression, aggregation |
| 11 | RAG Retrieval | Document chunk ingestion and search |
| 12 | Export Artifacts | CSV, Markdown, artifact generation |
| 13 | Context Budget | Token budget tracking and compression |
| 14 | Manifold Deep Dive | Surface construction and navigation |
| 15 | OpenAI Responses API | New Responses API format |
| 16 | Multi-Provider Benchmark | Side-by-side comparison of all 4 providers |
| 17 | Spatial Reasoning | Factor analysis with spatial tools |

Run an example:

```bash
./gradlew runExample -PmainClass=io.github.binomial-capital-management.binomialhash.examples.Example01_OpenAiAgentLoop
```

Live examples (01-03, 15-17) require API keys in a `.env` file — see `.env.example`.

## Package Structure

```
io.github.binomial-capital-management.binomialhash
├── BinomialHash             # Core — ingest, retrieve, aggregate, query, groupBy, schema
├── BinomialHashSlot         # Content-addressed storage slot
├── BinomialHashPolicy       # Compaction thresholds and limits
├── adapters/                # OpenAI, Anthropic, Gemini, xAI schema translators
├── context/                 # Request-scoped ThreadLocal helpers
├── examples/                # 17 runnable examples
├── exporters/               # Markdown, CSV, Excel, chunked artifacts
├── extract/                 # Row extraction from nested JSON
├── insights/                # Objective-driven insight extraction
├── manifold/                # Surface construction and spatial reasoning
├── middleware/               # Auto-interception decorator
├── predicates/              # Predicate building and row filtering
├── schema/                  # Schema inference and column typing
├── stats/                   # Statistical tools (regression, quality, drivers, etc.)
├── tokenizers/              # Provider-aware token counting
└── tools/                   # ToolSpec definitions
```

## Requirements

- Java 17+
- Jackson Databind 2.17+ (included)
- Commons Math 3.6+ (included)

## Development

```bash
git clone https://github.com/Binomial-Capital-Management/binomialhash-jvm.git
cd binomialhash-jvm
./gradlew build
./gradlew test
```

## License

[MIT](LICENSE)
