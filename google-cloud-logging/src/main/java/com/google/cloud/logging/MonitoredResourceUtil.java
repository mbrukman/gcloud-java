/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.logging;

import com.google.cloud.MetadataConfig;
import com.google.cloud.MonitoredResource;
import com.google.cloud.ServiceOptions;
import com.google.cloud.logging.LogEntry.Builder;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitored resource construction utilities to detect resource type and add labels.
 * Used by logging framework adapters to configure default resource.
 * See usage in {@link LoggingHandler}.
 */
public class MonitoredResourceUtil {

  private enum Label {
    AppId("app_id"),
    ClusterName("cluster_name"),
    InstanceId("instance_id"),
    InstanceName("instance_name"),
    ModuleId("module_id"),
    ProjectId("project_id"),
    VersionId("version_id"),
    Zone("zone");

    private final String key;

    Label(String key) {
      this.key = key;
    }

    String getKey() {
      return key;
    }
  }

  private enum Resource {
    Container("container"),
    GaeAppFlex("gae_app_flex"),
    GaeAppStandard("gae_app_standard"),
    GceInstance("gce_instance"),
    Global("global");

    private final String key;

    Resource(String key) {
      this.key = key;
    }

    String getKey() {
      return key;
    }
  }

  private static final String APPENGINE_LABEL_PREFIX = "appengine.googleapis.com/";

  private static Map<String, Label[]> resourceTypeWithLabels;

  static {
    resourceTypeWithLabels =
        new ImmutableMap.Builder<String, Label[]>()
            .put(
                Resource.GaeAppFlex.getKey(),
                new Label[] {
                    Label.ModuleId,
                    Label.VersionId,
                    Label.Zone
                })
            .put(
                Resource.GaeAppStandard.getKey(),
                new Label[] {Label.ModuleId, Label.VersionId})
            .put(Resource.Container.getKey(), new Label[] {Label.ClusterName, Label.Zone})
            .put(Resource.GceInstance.getKey(), new Label[] {Label.InstanceId, Label.Zone})
            .build();
  }

  private MonitoredResourceUtil() {
  }

  /* Return a self-configured monitored Resource. */
  public static MonitoredResource getResource(String projectId, String resourceTypeParam) {
    String resourceType = resourceTypeParam;
    if (Strings.isNullOrEmpty(resourceType)) {
      Resource detectedResourceType = getAutoDetectedResourceType();
      resourceType = detectedResourceType.getKey();
    }
    // Currently, "gae_app" is the supported logging Resource type, but we distinguish
    // between "gae_app_flex", "gae_app_standard" to support zone id, instance name logging on flex VMs.
    // Hence, "gae_app_flex", "gae_app_standard" are trimmed to "gae_app"
    String resourceName = resourceType.startsWith("gae_app") ? "gae_app" : resourceType;
    MonitoredResource.Builder builder =
        MonitoredResource.newBuilder(resourceName).addLabel(Label.ProjectId.getKey(), projectId);
    Label[] resourceLabels = resourceTypeWithLabels.get(resourceType);
    if (resourceLabels != null) {
      for (Label label : resourceLabels) {
        String value = getValue(label);
        if (value != null) {
          builder.addLabel(label.getKey(), value);
        }
      }
    }
    return builder.build();
  }

  /**
   * Returns custom log entry enhancers (if available) for resource type.
   *
   * @return custom log entry enhancers
   */
  public static List<LoggingEnhancer> getResourceEnhancers() {
    Resource resourceType = getAutoDetectedResourceType();
    return createEnhancers(resourceType);
  }

  private static String getValue(Label label) {
    String value;
    switch (label) {
      case AppId:
        value = ServiceOptions.getAppEngineAppId();
        break;
      case ClusterName:
        value = MetadataConfig.getClusterName();
        break;
      case InstanceId:
        value = MetadataConfig.getInstanceId();
        break;
      case InstanceName:
        value = getAppEngineInstanceName();
        break;
      case ModuleId:
        value = getAppEngineModuleId();
        break;
      case VersionId:
        value = getAppEngineVersionId();
        break;
      case Zone:
        value = MetadataConfig.getZone();
        break;
      default:
        value = null;
        break;
    }
    return value;
  }

  /* Detect monitored Resource type using environment variables, else return global as default. */
  private static Resource getAutoDetectedResourceType() {
    if (System.getenv("GAE_INSTANCE") != null) {
      return Resource.GaeAppFlex;
    }
    if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
      return Resource.Container;
    }
    if (ServiceOptions.getAppEngineAppId() != null) {
      return Resource.GaeAppStandard;
    }
    if (MetadataConfig.getInstanceId() != null) {
      return Resource.GceInstance;
    }
    // default Resource type
    return Resource.Global;
  }

  private static String getAppEngineModuleId() {
    return System.getenv("GAE_SERVICE");
  }

  private static String getAppEngineVersionId() {
    return System.getenv("GAE_VERSION");
  }

  private static String getAppEngineInstanceName() {
    return System.getenv("GAE_INSTANCE");
  }

  private static List<LoggingEnhancer> createEnhancers(Resource resourceType) {
    List<LoggingEnhancer> enhancers = new ArrayList<>(2);
    switch (resourceType) {
      // Trace logging enhancer is supported on GAE Flex and Standard.
      case GaeAppFlex:
        enhancers.add(new LabelLoggingEnhancer(
            APPENGINE_LABEL_PREFIX, Collections.singletonList(Label.InstanceName)));
        enhancers.add(new TraceLoggingEnhancer(APPENGINE_LABEL_PREFIX));
        break;
      case GaeAppStandard:
        enhancers.add(new TraceLoggingEnhancer(APPENGINE_LABEL_PREFIX));
        break;
      default:
        break;
    }
    return enhancers;
  }

  /**
   * Adds additional resource-based labels to log entries.
   * Labels that can be provided with {@link MonitoredResource.Builder#addLabel(String, String)}
   * are restricted to a supported set per resource.
   *
   * @see <a href="https://cloud.google.com/logging/docs/api/v2/resource-list">Logging Labels</a>
   */
  private static class LabelLoggingEnhancer implements LoggingEnhancer {

    private final Map<String, String> labels;

    LabelLoggingEnhancer(String prefix, List<Label> labelNames) {
      labels = new HashMap<>();
      if (labelNames != null) {
        for (Label labelName : labelNames) {
          String labelValue = MonitoredResourceUtil.getValue(labelName);
          if (labelValue != null) {
            String fullLabelName = (prefix != null) ?
                prefix + labelName.getKey() : labelName.getKey();
            labels.put(fullLabelName, labelValue);
          }
        }
      }
    }

    @Override
    public void enhanceLogEntry(Builder logEntry) {
      for (Map.Entry<String, String> label : labels.entrySet()) {
        logEntry.addLabel(label.getKey(), label.getValue());
      }
    }
  }
}
