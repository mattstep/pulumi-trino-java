/*
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
package step.aero;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.pulumi.Context;
import com.pulumi.aws.amp.Workspace;
import com.pulumi.aws.amp.WorkspaceArgs;
import com.pulumi.aws.eks.PodIdentityAssociation;
import com.pulumi.aws.eks.PodIdentityAssociationArgs;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementPrincipalArgs;
import com.pulumi.aws.iam.inputs.RoleInlinePolicyArgs;
import com.pulumi.aws.iam.outputs.GetPolicyDocumentResult;
import com.pulumi.aws.ssm.SsmFunctions;
import com.pulumi.aws.ssm.inputs.GetParameterArgs;
import com.pulumi.aws.ssm.outputs.GetParameterResult;
import com.pulumi.awsx.ec2.Vpc;
import com.pulumi.core.Output;
import com.pulumi.eks.Cluster;
import com.pulumi.eks.ClusterArgs;
import com.pulumi.kubernetes.Provider;
import com.pulumi.kubernetes.ProviderArgs;
import com.pulumi.kubernetes.helm.v3.Release;
import com.pulumi.kubernetes.helm.v3.ReleaseArgs;
import com.pulumi.kubernetes.helm.v3.inputs.RepositoryOptsArgs;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

import static com.pulumi.aws.iam.IamFunctions.getPolicyDocument;

public class TrinoCluster
{
    private static Workspace prometheusWorkspace;
    private Cluster eksCluster;
    private Release trinoHelmRelease;
    private Release otelCollector;
    private Release jeagerRelease;

    public TrinoCluster(Context context)
    {
        Vpc vpc = new Vpc("trino-demo");

        eksCluster = new Cluster("trino-demo",
                ClusterArgs.builder()
                        .vpcId(vpc.vpcId())
                        .publicSubnetIds(vpc.publicSubnetIds())
                        .privateSubnetIds(vpc.privateSubnetIds())
                        .instanceType("t4g.xlarge")
                        .nodeAmiId(SsmFunctions.getParameter(GetParameterArgs.builder()
                                .name("/aws/service/eks/optimized-ami/1.29/amazon-linux-2-arm64/recommended/image_id")
                                .build()).applyValue(GetParameterResult::value))
                        .minSize(5)
                        .desiredCapacity(5)
                        .maxSize(15)
                        .build());

        prometheusWorkspace = new Workspace("trino", WorkspaceArgs.builder()
                .alias("trino-metrics")
                .tags(Map.of("Environment", "production"))
                .build());

        Role ampIngestRole = createAmpIngestRole();

        Provider kubernetesProvider = new Provider("trino-demo",
                ProviderArgs.builder()
                        .kubeconfig(eksCluster.kubeconfigJson())
                        .build());

        String tracingConfig = """
                tracing.enabled=true
                tracing.exporter.endpoint=http://jaeger-collector:4317""";

        Map<String, String> prometheusAnnotations = ImmutableMap.of(
                "prometheus.io/path", "metrics",
                "prometheus.io/port", String.valueOf(8080),
                "prometheus.io/scrape", "true");

        trinoHelmRelease = new Release("trino-helm",
                ReleaseArgs.builder()
                        .chart("trino")
                        .version("0.23.1")
                        .values(ImmutableMap.of(
                                "service", ImmutableMap.of("type", "LoadBalancer"),
                                "server", ImmutableMap.of(
                                        "coordinatorExtraConfig", tracingConfig,
                                        "workerExtraConfig", tracingConfig),
                                "coordinator", ImmutableMap.of(
                                        "annotations", prometheusAnnotations),
                                "worker", ImmutableMap.of(
                                        "annotations", prometheusAnnotations)))
                        .repositoryOpts(
                                RepositoryOptsArgs.builder()
                                        .repo("https://trinodb.github.io/charts")
                                        .build())
                        .build(),
                CustomResourceOptions.builder()
                        .provider(kubernetesProvider)
                        .build());

        jeagerRelease = new Release("jaeger",
                ReleaseArgs.builder()
                        .chart("jaeger")
                        .name("jaeger")
                        .version("3.0.10")
                        .values(ImmutableMap.of(
                                "query", ImmutableMap.of(
                                        "service", ImmutableMap.of("type", "LoadBalancer")),
                                "fullnameOverride", "jaeger",
                                "collector", ImmutableMap.of(
                                        "service", ImmutableMap.of(
                                                "grpc", ImmutableMap.of("port", "14250"),
                                                "http", ImmutableMap.of("port", "14268"),
                                                "otlp", ImmutableMap.of(
                                                        "http", ImmutableMap.of(
                                                                "name", "otlp-http",
                                                                "port", "4318"),
                                                        "grpc", ImmutableMap.of(
                                                                "name", "otlp-grpc",
                                                                "port", "4317"))))))
                        .repositoryOpts(
                                RepositoryOptsArgs.builder()
                                        .repo("https://jaegertracing.github.io/helm-charts")
                                        .build())
                        .build(),
                CustomResourceOptions.builder()
                        .provider(kubernetesProvider)
                        .build());

        otelCollector = new Release("otel-collector",
                ReleaseArgs.builder()
                        .chart("opentelemetry-collector")
                        .version("0.86.0")
                        .name("otel-collector")
                        .values(ImmutableMap.<String, Object>builder()
                                .put("image", ImmutableMap.of("repository", "otel/opentelemetry-collector-contrib"))
                                .put("mode", "daemonset")
                                .put("config", ImmutableMap.of(
                                        "exporters", ImmutableMap.of(
                                                "otlp/jaeger", ImmutableMap.of(
                                                        "endpoint", "http://jaeger-collector:4317"),
                                                "prometheusremotewrite", ImmutableMap.of(
                                                        "endpoint", String.format("%sapi/v1/remote_write", prometheusWorkspace.prometheusEndpoint()),
                                                        "auth", ImmutableMap.of("authenticator", "sigv4auth"),
                                                        "resource_to_telemetry_conversion", ImmutableMap.of("enabled", "true"))),
                                        "extensions", ImmutableMap.of(
                                                "health_check", ImmutableMap.of(),
                                                "memory_ballast", ImmutableMap.of(),
                                                "sigv4auth", ImmutableMap.of(
                                                        "assume_role", ImmutableMap.of(
                                                                "arn", ampIngestRole.arn(),
                                                                "sts_region", "us-east-1"))),
                                        "processors", ImmutableMap.of(
                                                "batch", ImmutableMap.of(),
                                                "memory_limiter", ImmutableMap.of(),
                                                "k8sattributes/1", ImmutableMap.of(
                                                        "auth_type", "serviceAccount",
                                                        "passthrough", false,
                                                        "filter", ImmutableMap.of(
                                                                "node_from_env_var", "K8S_NODE_NAME"),
                                                        "extract", ImmutableMap.of(
                                                                "metadata", ImmutableList.of("k8s.pod.name", "k8s.pod.uid", "k8s.deployment.name", "k8s.namespace.name", "k8s.node.name", "k8s.pod.start_time"),
                                                                "pod_association", ImmutableList.of(
                                                                        ImmutableMap.of("sources", ImmutableList.of(
                                                                                ImmutableMap.of(
                                                                                        "from", "resource_attribute",
                                                                                        "name", "k8s.pod.ip"))),
                                                                        ImmutableMap.of("sources", ImmutableList.of(
                                                                                ImmutableMap.of(
                                                                                        "from", "resource_attribute",
                                                                                        "name", "k8s.pod.uid"))),
                                                                        ImmutableMap.of("sources", ImmutableList.of(
                                                                                ImmutableMap.of("from", "connection"))))))),
                                        "receivers", ImmutableMap.of(
                                                "otlp", ImmutableMap.of(
                                                        "protocols", ImmutableMap.of(
                                                                "grpc", ImmutableMap.of(
                                                                        "endpoint", "0.0.0.0:4317"))),
                                                "prometheus", ImmutableMap.of(
                                                        "config", ImmutableMap.of(
                                                                "scrape_configs", ImmutableList.of(
                                                                        ImmutableMap.of(
                                                                                "job_name", "k8s",
                                                                                "scrape_interval", "15s",
                                                                                "kubernetes_sd_configs", ImmutableList.of(
                                                                                        ImmutableMap.of("role", "pod")),
                                                                                "relabel_configs", ImmutableList.of(
                                                                                        ImmutableMap.of(
                                                                                                "source_labels", ImmutableList.of("__meta_kubernetes_pod_node_name"),
                                                                                                "regex", "${K8S_NODE_NAME}",
                                                                                                "action", "keep"),
                                                                                        ImmutableMap.of(
                                                                                                "source_labels", ImmutableList.of("__meta_kubernetes_pod_annotation_prometheus_io_scrape"),
                                                                                                "regex", true,
                                                                                                "action", "keep"),
                                                                                        ImmutableMap.of(
                                                                                                "source_labels", ImmutableList.of("__meta_kubernetes_pod_annotation_prometheus_io_path"),
                                                                                                "target_label", "__metrics_path__",
                                                                                                "regex", "$(.+)",
                                                                                                "action", "replace"),
                                                                                        ImmutableMap.of(
                                                                                                "source_labels", ImmutableList.of(
                                                                                                        "__meta_kubernetes_pod_ip",
                                                                                                        "__meta_kubernetes_pod_annotation_prometheus_io_port"),
                                                                                                "target_label", "__address__",
                                                                                                "separator", ":",
                                                                                                "action", "replace"))))))),
                                        "service", ImmutableMap.of(
                                                "telemetry", ImmutableMap.of(
                                                        "metrics", ImmutableMap.of(
                                                                "address", "0.0.0.0:8888")),
                                                "extensions", ImmutableList.of("health_check", "memory_ballast", "sigv4auth"),
                                                "pipelines", ImmutableMap.of(
                                                        "metrics", ImmutableMap.of(
                                                                "exporters", ImmutableList.of("prometheusremotewrite", "otlp/jaeger"),
                                                                "processors", ImmutableList.of(
                                                                        "memory_limiter",
                                                                        "k8sattributes/1",
                                                                        "batch"),
                                                                "receivers", ImmutableList.of("otlp", "prometheus"))))))
                                .put("serviceAccount", ImmutableMap.of(
                                        "create", true,
                                        "name", "otel-agent"))
                                .put("clusterRole", ImmutableMap.of(
                                        "create", true,
                                        "name", "otel-agent",
                                        "rules", ImmutableList.of(
                                                ImmutableMap.of(
                                                        "apiGroups", ImmutableList.of(""),
                                                        "resources", ImmutableList.of("nodes/stats"),
                                                        "verbs", ImmutableList.of("get"))),
                                        "clusterRoleBinding", ImmutableMap.of(
                                                "name", "otel-agent")))
                                .put("priorityClassName", "system-node-critical")
                                .put("extraEnvs", ImmutableList.of(
                                        ImmutableMap.of(
                                                "name", "K8S_NODE_NAME",
                                                "valueFrom", ImmutableMap.of(
                                                        "fieldRef", ImmutableMap.of(
                                                                "fieldPath", "spec.nodeName")))))
                                .put("presets", ImmutableMap.of(
                                        "kubernetesAttributes", ImmutableMap.of(
                                                "enabled", true)))
                                .put("resources", ImmutableMap.of(
                                        "limits", ImmutableMap.of(
                                                "cpu", "256m",
                                                "memory", "512Mi")))
                                .put("podLabels", ImmutableMap.of(
                                        "app", "otel"))
                                .buildOrThrow())
                        .repositoryOpts(RepositoryOptsArgs.builder()
                                .repo("https://open-telemetry.github.io/opentelemetry-helm-charts")
                                .build())
                        .build(),
                CustomResourceOptions.builder()
                        .provider(kubernetesProvider)
                        .build());

        new PodIdentityAssociation("otel-collector", PodIdentityAssociationArgs.builder()
                .clusterName(eksCluster.eksCluster().applyValue(com.pulumi.aws.eks.Cluster::name).applyValue(String::valueOf))
                .namespace("default")
                .serviceAccount("otel-agent")
                .roleArn(ampIngestRole.arn())
                .build());
    }

    private static Output<GetPolicyDocumentResult> getAmpIngestPolicy()
    {
        return getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .actions("aps:RemoteWrite")
                        .effect("Allow")
                        .resources(prometheusWorkspace.arn()
                                .applyValue(arn -> arn)
                                .applyValue(String::valueOf)
                                .applyValue(List::of))
                        .build())
                .build());
    }

    private Role createAmpIngestRole()
    {
        Output<GetPolicyDocumentResult> instanceAssumeRolePolicy = getPolicyDocument(GetPolicyDocumentArgs.builder()
                .statements(GetPolicyDocumentStatementArgs.builder()
                        .actions("sts:AssumeRole", "sts:TagSession")
                        .principals(GetPolicyDocumentStatementPrincipalArgs.builder()
                                .type("Service")
                                .identifiers("pods.eks.amazonaws.com")
                                .build())
                        .build())
                .build());

        Output<GetPolicyDocumentResult> ampIngestPolicy = getAmpIngestPolicy();

        return new Role("amp-ingest", RoleArgs.builder()
                .name("amp-ingest")
                .assumeRolePolicy(instanceAssumeRolePolicy.applyValue(GetPolicyDocumentResult::json))
                .inlinePolicies(RoleInlinePolicyArgs.builder()
                        .name("AMPIngestPolicy")
                        .policy(ampIngestPolicy.applyValue(GetPolicyDocumentResult::json))
                        .build())
                .build());
    }
}
