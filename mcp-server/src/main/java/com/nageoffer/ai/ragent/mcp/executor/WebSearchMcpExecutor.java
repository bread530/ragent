/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索 MCP 工具执行器
 * 通过 DuckDuckGo HTML 搜索接口实现联网搜索功能，无需 API Key
 */
@Slf4j
@Component
public class WebSearchMcpExecutor {

    private static final String TOOL_ID = "web_search";

    private static final String DDG_SEARCH_URL = "https://html.duckduckgo.com/html/";
    private static final String BING_SEARCH_URL = "https://cn.bing.com/search";

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebSearchMcpExecutor() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification webSearchToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词，支持中英文以及多个关键词组合搜索"
        ));

        properties.put("maxResults", Map.of(
                "type", "integer",
                "description", "返回结果数量，默认5条，最多10条",
                "default", 5
        ));

        properties.put("language", Map.of(
                "type", "string",
                "description", "搜索语言偏好：zh-CN(中文)、en(英文)，默认zh-CN",
                "enum", List.of("zh-CN", "en"),
                "default", "zh-CN"
        ));

        properties.put("searchType", Map.of(
                "type", "string",
                "description", "搜索类型：general(通用搜索)、news(新闻搜索)，默认general",
                "enum", List.of("general", "news"),
                "default", "general"
        ));

        properties.put("timeRange", Map.of(
                "type", "string",
                "description", "时间范围筛选：不填则不限制，d(最近一天)、w(最近一周)、m(最近一月)、y(最近一年)",
                "enum", List.of("d", "w", "m", "y")
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("query"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("联网搜索工具，实时搜索互联网获取最新信息，支持通用网页搜索和新闻搜索，适用于需要最新资讯、实时数据或知识库未覆盖的内容")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String query = stringArg(args, "query");
            Integer maxResults = intArg(args, "maxResults");
            String language = stringArg(args, "language");
            String searchType = stringArg(args, "searchType");
            String timeRange = stringArg(args, "timeRange");

            if (query == null || query.isBlank()) {
                return errorResult("请提供搜索关键词");
            }
            if (maxResults == null || maxResults <= 0) maxResults = 5;
            if (maxResults > 10) maxResults = 10;
            if (language == null || language.isBlank()) language = "zh-CN";
            if (searchType == null || searchType.isBlank()) searchType = "general";

            List<SearchResult> results = performSearch(query, maxResults, language, searchType, timeRange);

            String formatted = formatResults(query, results, searchType, startMs);

            log.info("MCP 工具调用完成, toolId={}, query={}, resultCount={}, searchType={}, elapsed={}ms",
                    TOOL_ID, query, results.size(), searchType, System.currentTimeMillis() - startMs);
            return successResult(formatted);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return errorResult("联网搜索失败: " + e.getMessage());
        }
    }

    private List<SearchResult> performSearch(String query, int maxResults, String language,
                                              String searchType, String timeRange) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        List<Exception> errors = new ArrayList<>();

        // Try DuckDuckGo first
        try {
            return searchDuckDuckGo(encodedQuery, maxResults, language, searchType, timeRange);
        } catch (Exception e) {
            log.warn("DuckDuckGo 搜索失败, 尝试 Bing 降级: {}", e.getMessage());
            errors.add(e);
        }

        // Fallback to Bing
        try {
            return searchBing(encodedQuery, maxResults, language, searchType);
        } catch (Exception e) {
            log.warn("Bing 搜索也失败, 尝试 Wikipedia 降级: {}", e.getMessage());
            errors.add(e);
        }

        // Final fallback: Wikipedia API (usually accessible)
        try {
            return searchWikipedia(encodedQuery, maxResults, language);
        } catch (Exception e) {
            log.warn("Wikipedia 搜索也失败: {}", e.getMessage());
            errors.add(e);
        }

        throw new RuntimeException("所有搜索引擎均不可用，请稍后重试");
    }

    private List<SearchResult> searchDuckDuckGo(String encodedQuery, int maxResults, String language,
                                                 String searchType, String timeRange) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(DDG_SEARCH_URL);
        urlBuilder.append("?q=").append(encodedQuery);

        if ("news".equals(searchType)) {
            urlBuilder.append("&t=n");
        }
        if (timeRange != null && !timeRange.isBlank()) {
            urlBuilder.append("&df=").append(timeRange);
        }
        if (language != null && !language.isBlank()) {
            String kl = language.startsWith("zh") ? "cn-zh" : "us-en";
            urlBuilder.append("&kl=").append(kl);
        }

        String html = fetchHtml(urlBuilder.toString(), language);
        List<SearchResult> results = parseDdgHtml(html, maxResults);
        if (results.isEmpty()) {
            throw new RuntimeException("DuckDuckGo 未返回有效搜索结果");
        }
        return results;
    }

    private List<SearchResult> searchBing(String encodedQuery, int maxResults, String language,
                                           String searchType) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(BING_SEARCH_URL);
        urlBuilder.append("?q=").append(encodedQuery);
        urlBuilder.append("&setlang=").append(language != null && language.startsWith("zh") ? "zh-hans" : "en");
        if ("news".equals(searchType)) {
            urlBuilder.append("&qft=news");
        }

        String html = fetchHtml(urlBuilder.toString(), language);
        List<SearchResult> results = parseBingHtml(html, maxResults);
        if (results.isEmpty()) {
            throw new RuntimeException("Bing 未返回有效搜索结果（可能触发了反爬机制）");
        }
        return results;
    }

    private List<SearchResult> searchWikipedia(String encodedQuery, int maxResults,
                                                String language) throws Exception {
        String lang = (language != null && language.startsWith("zh")) ? "zh" : "en";
        String apiUrl = String.format(
                "https://%s.wikipedia.org/w/api.php?action=query&list=search&srsearch=%s&srlimit=%d&format=json",
                lang, encodedQuery, Math.min(maxResults, 10));

        log.info("Wikipedia 搜索, url={}", apiUrl);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", "RagentMCP/1.0 (Wikipedia Search)")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Wikipedia API 返回异常状态码: " + response.statusCode());
        }

        List<SearchResult> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode searchResults = root.path("query").path("search");

        if (searchResults.isArray()) {
            for (JsonNode item : searchResults) {
                if (results.size() >= maxResults) break;

                String title = item.path("title").asText();
                String snippet = item.path("snippet").asText(null);
                // Clean HTML from snippet
                if (snippet != null) {
                    snippet = TAG_PATTERN.matcher(snippet).replaceAll("");
                }
                String pageUrl = String.format("https://%s.wikipedia.org/wiki/%s",
                        lang, URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8));

                SearchResult result = new SearchResult();
                result.title = title;
                result.url = pageUrl;
                result.snippet = snippet != null ? snippet : "暂无摘要";
                results.add(result);
            }
        }

        if (results.isEmpty()) {
            throw new RuntimeException("Wikipedia 未找到相关结果");
        }
        return results;
    }

    private String fetchHtml(String url, String language) throws Exception {
        log.info("发起搜索请求, url={}", url);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", language + ",en;q=0.9")
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("搜索服务返回异常状态码: " + response.statusCode());
        }
        return response.body();
    }

    private List<SearchResult> parseDdgHtml(String html, int maxResults) {
        List<SearchResult> results = new ArrayList<>();

        Pattern linkPattern = Pattern.compile(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>\\s*([^<]+)\\s*</a>",
                Pattern.CASE_INSENSITIVE);
        Matcher linkMatcher = linkPattern.matcher(html);

        Pattern snippetPattern = Pattern.compile(
                "<a[^>]*class=\"result__snippet\"[^>]*>\\s*(.*?)\\s*</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        List<String> links = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        while (linkMatcher.find() && links.size() < maxResults) {
            String href = linkMatcher.group(1);
            String title = decodeHtmlEntities(linkMatcher.group(2).trim());
            if (href == null || href.isBlank() || title.isBlank()) continue;
            if (href.startsWith("//")) href = "https:" + href;
            links.add(href);
            titles.add(title);
        }

        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = snippetPattern.matcher(html);
        while (snippetMatcher.find() && snippets.size() < maxResults) {
            String snippet = decodeHtmlEntities(snippetMatcher.group(1).trim());
            snippet = TAG_PATTERN.matcher(snippet).replaceAll("");
            if (!snippet.isBlank()) snippets.add(snippet);
        }

        for (int i = 0; i < links.size(); i++) {
            SearchResult result = new SearchResult();
            result.title = titles.get(i);
            result.url = links.get(i);
            result.snippet = i < snippets.size() ? snippets.get(i) : "暂无摘要";
            results.add(result);
        }

        if (results.isEmpty()) {
            results.addAll(fallbackParse(html, maxResults));
        }
        return results;
    }

    private List<SearchResult> parseBingHtml(String html, int maxResults) {
        List<SearchResult> results = new ArrayList<>();

        // Bing result titles: <h2><a target="_blank" href="URL"><strong>Title</strong></a></h2>
        // or: <h2><a target="_blank" href="URL">Title</a></h2>
        Pattern titlePattern = Pattern.compile(
                "<h2[^>]*>\\s*<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.+?)</a>\\s*</h2>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // Bing result snippets: <p class="b_lineclamp2">text</p>
        Pattern snippetPattern = Pattern.compile(
                "<p[^>]*class=\"b_lineclamp\\d+\"[^>]*>(.+?)</p>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // Collect all titles and snippets
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        Matcher titleMatcher = titlePattern.matcher(html);
        while (titleMatcher.find() && titles.size() < maxResults) {
            String href = titleMatcher.group(1);
            String titleHtml = titleMatcher.group(2);
            String title = decodeHtmlEntities(TAG_PATTERN.matcher(titleHtml).replaceAll("").trim());

            if (href.contains("go.microsoft.com") || href.contains("bing.com")
                    || href.contains("microsoft.com")) continue;
            if (title.length() < 5 || title.length() > 200) continue;

            urls.add(href);
            titles.add(title);
        }

        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = snippetPattern.matcher(html);
        while (snippetMatcher.find() && snippets.size() < maxResults) {
            String snippet = decodeHtmlEntities(snippetMatcher.group(1).trim());
            snippet = TAG_PATTERN.matcher(snippet).replaceAll("").strip();
            if (snippet.length() > 15) {
                snippets.add(snippet);
            }
        }

        // Pair titles with snippets by position
        for (int i = 0; i < titles.size(); i++) {
            SearchResult result = new SearchResult();
            result.title = titles.get(i);
            result.url = urls.get(i);
            result.snippet = i < snippets.size() ? snippets.get(i) : "暂无摘要";
            results.add(result);
        }

        if (results.isEmpty()) {
            results.addAll(fallbackParse(html, maxResults));
        }
        return results;
    }

    private List<SearchResult> fallbackParse(String html, int maxResults) {
        List<SearchResult> results = new ArrayList<>();

        // Match links, excluding Bing footer (sb_* ids) and internal links
        Pattern altPattern = Pattern.compile(
                "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>([^<]+)</a>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = altPattern.matcher(html);

        while (matcher.find() && results.size() < maxResults) {
            String fullTag = matcher.group(0);
            String href = matcher.group(1);
            String title = decodeHtmlEntities(matcher.group(2).trim());

            // Skip footer/infrastructure links
            if (fullTag.contains("id=\"sb_")) continue;
            if (title.length() < 5 || title.length() > 200) continue;
            if (href.contains("duckduckgo.com") || href.contains("bing.com")
                    || href.contains("go.microsoft.com") || href.contains("live.com")) continue;

            SearchResult result = new SearchResult();
            result.title = title;
            result.url = href;
            result.snippet = "";
            results.add(result);
        }

        return results;
    }

    private String formatResults(String query, List<SearchResult> results, String searchType, long startMs) {
        StringBuilder sb = new StringBuilder();

        String typeLabel = "news".equals(searchType) ? "新闻搜索" : "通用搜索";
        sb.append(String.format("【联网搜索 - %s】\n", typeLabel));
        sb.append(String.format("搜索关键词: %s\n", query));
        sb.append(String.format("搜索结果: %d 条\n", results.size()));

        if (results.isEmpty()) {
            sb.append("\n未找到相关搜索结果，建议：\n");
            sb.append("1. 尝试更换关键词\n");
            sb.append("2. 减少关键词数量\n");
            sb.append("3. 使用更通用的词汇\n");
            return sb.toString().trim();
        }

        sb.append("\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(String.format("%d. %s\n", i + 1, r.title));
            sb.append(String.format("   链接: %s\n", r.url));
            if (r.snippet != null && !r.snippet.isBlank()) {
                sb.append(String.format("   摘要: %s\n", r.snippet));
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private String decodeHtmlEntities(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ")
                .replace("&ensp;", " ")
                .replaceAll("&#\\d+;", "")
                .strip();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    private static class SearchResult {
        String title;
        String url;
        String snippet;
    }
}
