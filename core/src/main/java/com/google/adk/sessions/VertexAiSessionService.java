/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.sessions;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connects to the managed Vertex AI Session Service. */
/** TODO: Use the genai HttpApiClient and ApiResponse methods once they are public. */
public final class VertexAiSessionService implements BaseSessionService {
  private static final int MAX_RETRY_ATTEMPTS = 5;
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final Logger logger = LoggerFactory.getLogger(VertexAiSessionService.class);

  private final HttpApiClient apiClient;

  /**
   * Creates a new instance of the Vertex AI Session Service with a custom ApiClient for testing.
   */
  public VertexAiSessionService(String project, String location, HttpApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public VertexAiSessionService() {
    this.apiClient =
        new HttpApiClient(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  public VertexAiSessionService(
      String project,
      String location,
      Optional<GoogleCredentials> credentials,
      Optional<HttpOptions> httpOptions) {
    this.apiClient =
        new HttpApiClient(Optional.of(project), Optional.of(location), credentials, httpOptions);
  }

  public JsonNode getJsonResponse(ApiResponse apiResponse) {
    try {
      ResponseBody responseBody = apiResponse.getResponseBody();
      return objectMapper.readTree(responseBody.string());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {

    String reasoningEngineId = parseReasoningEngineId(appName);
    ConcurrentHashMap<String, Object> sessionJsonMap = new ConcurrentHashMap<>();
    sessionJsonMap.put("userId", userId);
    if (state != null) {
      sessionJsonMap.put("sessionState", state);
    }

    ApiResponse apiResponse;
    try {
      apiResponse =
          apiClient.request(
              "POST",
              "reasoningEngines/" + reasoningEngineId + "/sessions",
              objectMapper.writeValueAsString(sessionJsonMap));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    logger.debug("Create Session response {}", apiResponse.getResponseBody());
    String sessionName = "";
    String operationId = "";
    String sessId = nullToEmpty(sessionId);
    if (apiResponse.getResponseBody() != null) {
      JsonNode jsonResponse = getJsonResponse(apiResponse);
      sessionName = jsonResponse.get("name").asText();
      List<String> parts = Splitter.on('/').splitToList(sessionName);
      sessId = parts.get(parts.size() - 3);
      operationId = Iterables.getLast(parts);
    }
    for (int i = 0; i < MAX_RETRY_ATTEMPTS; i++) {
      ApiResponse lroResponse = apiClient.request("GET", "operations/" + operationId, "");
      JsonNode jsonResponse = getJsonResponse(lroResponse);
      if (jsonResponse.get("done") != null) {
        break;
      }
      try {
        SECONDS.sleep(1);
      } catch (InterruptedException e) {
        logger.warn("Error during sleep", e);
      }
    }

    ApiResponse getSessionApiResponse =
        apiClient.request(
            "GET", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessId, "");
    JsonNode getSessionResponseMap = getJsonResponse(getSessionApiResponse);
    Instant updateTimestamp = Instant.parse(getSessionResponseMap.get("updateTime").asText());
    ConcurrentMap<String, Object> sessionState = null;
    try {
      if (getSessionResponseMap != null && getSessionResponseMap.has("sessionState")) {
        JsonNode sessionStateNode = getSessionResponseMap.get("sessionState");
        if (sessionStateNode != null) {
          sessionState =
              objectMapper.readValue(
                  sessionStateNode.toString(),
                  new TypeReference<ConcurrentMap<String, Object>>() {});
        }
      }
    } catch (JsonProcessingException e) {
      logger.warn("Error while parsing session state: {}", e.getMessage());
    }
    return Single.just(
        Session.builder(sessId)
            .appName(appName)
            .userId(userId)
            .lastUpdateTime(updateTimestamp)
            .state(sessionState == null ? new ConcurrentHashMap<>() : sessionState)
            .build());
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    String reasoningEngineId = parseReasoningEngineId(appName);

    ApiResponse apiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions?filter=user_id=" + userId,
            "");

    // Handles empty response case
    if (apiResponse.getResponseBody() == null) {
      return Single.just(ListSessionsResponse.builder().build());
    }

    JsonNode listSessionsResponseMap = getJsonResponse(apiResponse);
    List<Map<String, Object>> apiSessions;
    try {
      apiSessions =
          objectMapper.readValue(
              listSessionsResponseMap.get("sessions").toString(),
              new TypeReference<List<Map<String, Object>>>() {});
    } catch (JsonProcessingException e) {
      logger.warn("Error while parsing list sessions response: {}", e.getMessage());
      apiSessions = new ArrayList<>();
    }

    List<Session> sessions = new ArrayList<>();
    for (Map<String, Object> apiSession : apiSessions) {
      String name = (String) apiSession.get("name");
      List<String> parts = Splitter.on('/').splitToList(name);
      String sessionId = Iterables.getLast(parts);
      Instant updateTimestamp = Instant.parse((String) apiSession.get("updateTime"));
      Session session =
          Session.builder(sessionId)
              .appName(appName)
              .userId(userId)
              .state(new ConcurrentHashMap<>())
              .lastUpdateTime(updateTimestamp)
              .build();
      sessions.add(session);
    }
    return Single.just(ListSessionsResponse.builder().sessions(sessions).build());
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    String reasoningEngineId = parseReasoningEngineId(appName);
    ApiResponse apiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + "/events",
            "");

    logger.debug("List events response {}", apiResponse);

    if (apiResponse.getResponseBody() == null) {
      return Single.just(ListEventsResponse.builder().build());
    }

    JsonNode getEventsResponseMap = getJsonResponse(apiResponse);
    List<ConcurrentMap<String, Object>> listEventsResponse;
    try {
      listEventsResponse =
          objectMapper.readValue(
              getEventsResponseMap.get("sessionEvents").toString(),
              new TypeReference<List<ConcurrentMap<String, Object>>>() {});
    } catch (JsonProcessingException e) {
      logger.warn("Error while parsing list events response: {}", e.getMessage());
      listEventsResponse = new ArrayList<>();
    }

    List<Event> events = new ArrayList<>();
    for (Map<String, Object> event : listEventsResponse) {
      events.add(fromApiEvent(event));
    }
    return Single.just(ListEventsResponse.builder().events(events).build());
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    String reasoningEngineId = parseReasoningEngineId(appName);
    ApiResponse apiResponse =
        apiClient.request(
            "GET", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "");
    JsonNode getSessionResponseMap = getJsonResponse(apiResponse);

    String sessId =
        Optional.ofNullable(getSessionResponseMap.get("name"))
            .map(name -> Iterables.getLast(Splitter.on('/').splitToList(name.asText())))
            .orElse(sessionId);
    Instant updateTimestamp =
        Optional.ofNullable(getSessionResponseMap.get("updateTime"))
            .map(updateTime -> Instant.parse(updateTime.asText()))
            .orElse(null);

    ConcurrentMap<String, Object> sessionState = new ConcurrentHashMap<>();
    try {
      if (getSessionResponseMap != null && getSessionResponseMap.has("sessionState")) {
        sessionState =
            objectMapper.readValue(
                getSessionResponseMap.get("sessionState").toString(),
                new TypeReference<ConcurrentMap<String, Object>>() {});
      }
    } catch (JsonProcessingException e) {
      logger.warn("Error while parsing session state: {}", e.getMessage());
    }
    Session.Builder sessionBuilder =
        Session.builder(sessId)
            .appName(appName)
            .userId(userId)
            .lastUpdateTime(updateTimestamp)
            .state(sessionState);

    ApiResponse listEventsApiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + "/events",
            "");

    if (listEventsApiResponse.getResponseBody() == null) {
      return Maybe.just(sessionBuilder.build());
    }

    JsonNode getEventsResponseMap = getJsonResponse(listEventsApiResponse);
    List<Map<String, Object>> listEventsResponse = new ArrayList<>();
    try {
      if (getEventsResponseMap != null && getEventsResponseMap.has("sessionEvents")) {
        JsonNode sessionEventsNode = getEventsResponseMap.get("sessionEvents");
        if (sessionEventsNode != null && !sessionEventsNode.isNull()) {
          listEventsResponse =
              objectMapper.readValue(
                  sessionEventsNode.toString(), new TypeReference<List<Map<String, Object>>>() {});
        }
      }
    } catch (JsonProcessingException e) {
      logger.warn("Error while parsing session events: {}", e.getMessage());
    }

    List<Event> events =
        listEventsResponse.stream()
            .map(this::fromApiEvent)
            .filter(
                event ->
                    updateTimestamp == null
                        || Instant.ofEpochMilli(event.timestamp()).isBefore(updateTimestamp))
            .sorted(Comparator.comparing(Event::timestamp))
            .collect(toCollection(ArrayList::new));

    if (config.isPresent()) {
      if (config.get().numRecentEvents().isPresent()) {
        int numRecentEvents = config.get().numRecentEvents().get();
        if (events.size() > numRecentEvents) {
          events = events.subList(events.size() - numRecentEvents, events.size());
        }
      } else if (config.get().afterTimestamp().isPresent()) {
        Instant afterTimestamp = config.get().afterTimestamp().get();
        int i = events.size() - 1;
        while (i >= 0) {
          if (Instant.ofEpochMilli(events.get(i).timestamp()).isBefore(afterTimestamp)) {
            break;
          }
          i -= 1;
        }
        if (i >= 0) {
          events = events.subList(i, events.size());
        }
      }
    }

    return Maybe.just(sessionBuilder.events(events).build());
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    String reasoningEngineId = parseReasoningEngineId(appName);
    ApiResponse unused =
        apiClient.request(
            "DELETE", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "");
    return Completable.complete();
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    BaseSessionService.super.appendEvent(session, event);

    String reasoningEngineId = parseReasoningEngineId(session.appName());
    ApiResponse response =
        apiClient.request(
            "POST",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + session.id() + ":appendEvent",
            convertEventToJson(event));
    // TODO(b/414263934)): Improve error handling for appendEvent.
    try {
      if (response.getResponseBody().string().contains("com.google.genai.errors.ClientException")) {
        System.err.println("Failed to append event: " + event);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    response.close();
    return Single.just(event);
  }

  public String convertEventToJson(Event event) {
    Map<String, Object> metadataJson = new HashMap<>();
    metadataJson.put("partial", event.partial());
    metadataJson.put("turnComplete", event.turnComplete());
    metadataJson.put("interrupted", event.interrupted());
    metadataJson.put("branch", event.branch().orElse(null));
    metadataJson.put(
        "long_running_tool_ids",
        event.longRunningToolIds() != null ? event.longRunningToolIds().orElse(null) : null);
    if (event.groundingMetadata() != null) {
      metadataJson.put("grounding_metadata", event.groundingMetadata());
    }

    Map<String, Object> eventJson = new HashMap<>();
    eventJson.put("author", event.author());
    eventJson.put("invocationId", event.invocationId());
    eventJson.put(
        "timestamp",
        new HashMap<>(
            ImmutableMap.of(
                "seconds",
                event.timestamp() / 1000,
                "nanos",
                (event.timestamp() % 1000) * 1000000)));
    eventJson.put("errorCode", event.errorCode());
    eventJson.put("errorMessage", event.errorMessage());
    eventJson.put("eventMetadata", metadataJson);

    if (event.actions() != null) {
      Map<String, Object> actionsJson = new HashMap<>();
      actionsJson.put("skipSummarization", event.actions().skipSummarization());
      actionsJson.put("stateDelta", event.actions().stateDelta());
      actionsJson.put("artifactDelta", event.actions().artifactDelta());
      actionsJson.put("transferAgent", event.actions().transferToAgent());
      actionsJson.put("escalate", event.actions().escalate());
      actionsJson.put("requestedAuthConfigs", event.actions().requestedAuthConfigs());
      eventJson.put("actions", actionsJson);
    }
    if (event.content().isPresent()) {
      eventJson.put("content", SessionUtils.encodeContent(event.content().get()));
    }
    if (event.errorCode().isPresent()) {
      eventJson.put("errorCode", event.errorCode().get());
    }
    if (event.errorMessage().isPresent()) {
      eventJson.put("errorMessage", event.errorMessage().get());
    }
    try {
      return objectMapper.writeValueAsString(eventJson);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nullable
  @SuppressWarnings("unchecked")
  private Content convertMapToContent(Object rawContentValue) {
    if (rawContentValue == null) {
      return null;
    }

    if (rawContentValue instanceof Map) {
      Map<String, Object> contentMap = (Map<String, Object>) rawContentValue;
      try {
        return objectMapper.convertValue(contentMap, Content.class);
      } catch (IllegalArgumentException e) {
        System.err.println("Error converting Map to Content: " + e.getMessage());
        return null;
      }
    } else {
      System.err.println(
          "Unexpected type for 'content' in apiEvent: " + rawContentValue.getClass().getName());
      return null;
    }
  }

  public static String parseReasoningEngineId(String appName) {
    if (appName.matches("\\d+")) {
      return appName;
    }

    Matcher matcher = APP_NAME_PATTERN.matcher(appName);

    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "App name "
              + appName
              + " is not valid. It should either be the full"
              + " ReasoningEngine resource name, or the reasoning engine id.");
    }

    return matcher.group(matcher.groupCount());
  }

  @SuppressWarnings("unchecked")
  public Event fromApiEvent(Map<String, Object> apiEvent) {
    EventActions eventActions = new EventActions();
    if (apiEvent.get("actions") != null) {
      Map<String, Object> actionsMap = (Map<String, Object>) apiEvent.get("actions");
      eventActions.setSkipSummarization(
          Optional.ofNullable(actionsMap.get("skipSummarization")).map(value -> (Boolean) value));
      eventActions.setStateDelta(
          actionsMap.get("stateDelta") != null
              ? new ConcurrentHashMap<>((Map<String, Object>) actionsMap.get("stateDelta"))
              : new ConcurrentHashMap<>());
      eventActions.setArtifactDelta(
          actionsMap.get("artifactDelta") != null
              ? new ConcurrentHashMap<>((Map<String, Part>) actionsMap.get("artifactDelta"))
              : new ConcurrentHashMap<>());
      eventActions.setTransferToAgent(
          actionsMap.get("transferAgent") != null
              ? (String) actionsMap.get("transferAgent")
              : null);
      eventActions.setEscalate(
          Optional.ofNullable(actionsMap.get("escalate")).map(value -> (Boolean) value));
      eventActions.setRequestedAuthConfigs(
          actionsMap.get("requestedAuthConfigs") != null
              ? (ConcurrentMap<String, ConcurrentMap<String, Object>>)
                  actionsMap.get("requestedAuthConfigs")
              : new ConcurrentHashMap<>());
    }

    Event event =
        Event.builder()
            .id(
                (String)
                    Iterables.get(
                        Splitter.on('/').split(apiEvent.get("name").toString()),
                        apiEvent.get("name").toString().split("/").length - 1))
            .invocationId((String) apiEvent.get("invocationId"))
            .author((String) apiEvent.get("author"))
            .actions(eventActions)
            .content(
                Optional.ofNullable(apiEvent.get("content"))
                    .map(this::convertMapToContent)
                    .map(SessionUtils::decodeContent)
                    .orElse(null))
            .timestamp(Instant.parse((String) apiEvent.get("timestamp")).toEpochMilli())
            .errorCode(
                Optional.ofNullable(apiEvent.get("errorCode"))
                    .map(value -> new FinishReason((String) value)))
            .errorMessage(
                Optional.ofNullable(apiEvent.get("errorMessage")).map(value -> (String) value))
            .branch(Optional.ofNullable(apiEvent.get("branch")).map(value -> (String) value))
            .build();
    // TODO(b/414263934): Add Event branch and grounding metadata for python parity.
    if (apiEvent.get("eventMetadata") != null) {
      Map<String, Object> eventMetadata = (Map<String, Object>) apiEvent.get("eventMetadata");
      List<String> longRunningToolIdsList = (List<String>) eventMetadata.get("longRunningToolIds");

      GroundingMetadata groundingMetadata = null;
      Object rawGroundingMetadata = eventMetadata.get("groundingMetadata");
      if (rawGroundingMetadata != null) {
        groundingMetadata =
            objectMapper.convertValue(rawGroundingMetadata, GroundingMetadata.class);
      }

      event =
          event.toBuilder()
              .partial(Optional.ofNullable((Boolean) eventMetadata.get("partial")).orElse(false))
              .turnComplete(
                  Optional.ofNullable((Boolean) eventMetadata.get("turnComplete")).orElse(false))
              .interrupted(
                  Optional.ofNullable((Boolean) eventMetadata.get("interrupted")).orElse(false))
              .branch(Optional.ofNullable((String) eventMetadata.get("branch")))
              .groundingMetadata(groundingMetadata)
              .longRunningToolIds(
                  longRunningToolIdsList != null ? new HashSet<>(longRunningToolIdsList) : null)
              .build();
    }
    return event;
  }

  private static final Pattern APP_NAME_PATTERN =
      Pattern.compile(
          "^projects/([a-zA-Z0-9-_]+)/locations/([a-zA-Z0-9-_]+)/reasoningEngines/(\\d+)$");
}
