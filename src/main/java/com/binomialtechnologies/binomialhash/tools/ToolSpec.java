package com.binomialtechnologies.binomialhash.tools;

import java.util.Map;
import java.util.function.Function;

/**
 * One provider-neutral tool definition.
 *
 * <p>A ToolSpec holds everything needed to register a BinomialHash tool with
 * any LLM provider: name, description, a JSON Schema for inputs, and a
 * handler callable. Adapters (openai, anthropic, gemini, xai) translate
 * ToolSpecs into provider-specific tool definitions at registration time.</p>
 */
public class ToolSpec {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Function<Map<String, Object>, Object> handler;
    private final String group;

    public ToolSpec(String name, String description, Map<String, Object> inputSchema,
                    Function<Map<String, Object>, Object> handler, String group) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.handler = handler;
        this.group = group;
    }

    public ToolSpec(String name, String description, Map<String, Object> inputSchema,
                    Function<Map<String, Object>, Object> handler) {
        this(name, description, inputSchema, handler, "");
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public Function<Map<String, Object>, Object> getHandler() { return handler; }
    public String getGroup() { return group; }
}
