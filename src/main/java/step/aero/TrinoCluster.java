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
import com.pulumi.aws.ssm.SsmFunctions;
import com.pulumi.aws.ssm.inputs.GetParameterArgs;
import com.pulumi.aws.ssm.outputs.GetParameterResult;
import com.pulumi.awsx.ec2.Vpc;
import com.pulumi.eks.Cluster;
import com.pulumi.eks.ClusterArgs;
import com.pulumi.kubernetes.Provider;
import com.pulumi.kubernetes.ProviderArgs;
import com.pulumi.kubernetes.helm.v3.Release;
import com.pulumi.kubernetes.helm.v3.ReleaseArgs;
import com.pulumi.kubernetes.helm.v3.inputs.RepositoryOptsArgs;
import com.pulumi.resources.CustomResourceOptions;

public class TrinoCluster
{
    private final Cluster eksCluster;
    private final Release trinoHelmRelease;
    private final Release otelCollector;
    private final Release jeagerRelease;

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

        Provider kubernetesProvider = new Provider("trino-demo",
                ProviderArgs.builder()
                        .kubeconfig(eksCluster.kubeconfigJson())
                        .build());

        ImmutableMap tracingConfig = ImmutableMap.of(
                "tracing.enabled", "true",
                "tracing.exporter.endpoint", "http://jaeger-collector:4317");

        trinoHelmRelease = new Release("trino-helm",
                ReleaseArgs.builder()
                        .chart("trino")
                        .version("0.23.1")
                        .values(ImmutableMap.of(
                                "service", ImmutableMap.of("type", "LoadBalancer"),
                                "server", ImmutableMap.of(
                                        "extraCooordinatorConfig", tracingConfig,
                                        "extraWorkerConfig", tracingConfig)))
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
                                .put("mode", "deployment")
                                .put("config", ImmutableMap.of(
                                        "exporters", ImmutableMap.of(
                                                "otlp/jaeger", ImmutableMap.of(
                                                        "endpoint", "http://jaeger-collector:4317")),
                                        "processors", ImmutableMap.of(
                                                "batch", ImmutableMap.of()),
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
                                                "extensions", ImmutableList.of("health_check", "memory_ballast"),
                                                "pipelines", ImmutableMap.of(
                                                        "traces", ImmutableMap.of(
                                                                "exporters", ImmutableList.of("otlp/jaeger"),
                                                                "processors", ImmutableList.of("memory_limiter", "batch"),
                                                                "receivers", ImmutableList.of("otlp"))))))
                                .build())
                        .repositoryOpts(RepositoryOptsArgs.builder()
                                .repo("https://open-telemetry.github.io/opentelemetry-helm-charts")
                                .build())
                        .build(),
                CustomResourceOptions.builder()
                        .provider(kubernetesProvider)
                        .build());
    }
}
