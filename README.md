# OpenNMS Prometheus RemoteWrite Plugin

[![CircleCI](https://dl.circleci.com/status-badge/img/gh/OpenNMS-Plugins/opennms-prometheus-remotewrite-plugin/tree/main.svg?style=svg)](https://dl.circleci.com/status-badge/redirect/gh/OpenNMS-Plugins/opennms-prometheus-remotewrite-plugin/tree/main)
[![Release](https://img.shields.io/github/v/release/OpenNMS-Plugins/opennms-prometheus-remotewrite-plugin?sort=semver)](https://github.com/OpenNMS-Plugins/opennms-prometheus-remotewrite-plugin/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE.md)

This plugin exposes an implementation of the [TimeSeriesStorage](https://github.com/OpenNMS/opennms-integration-api/blob/v0.4.1/api/src/main/java/org/opennms/integration/api/v1/timeseries/TimeSeriesStorage.java#L40) interface that converts metrics to a Prometheus model and delegates writes & reads via the Prometheus `remote_write` / `remote_read` protocol to any compatible backend (e.g. [Cortex](https://cortexmetrics.io/), Mimir, Thanos, VictoriaMetrics, Prometheus itself).

> **Note:** This plugin was previously published as `opennms-cortex-tss-plugin`. It has been renamed to reflect that it works with any Prometheus `remote_write`-compatible backend, not just Cortex.
>
> **Upgrading from a previous release:** The OSGi configuration PID has been renamed from `org.opennms.plugins.tss.cortex` to `org.opennms.plugins.tss.prometheus`. Rename your `etc/org.opennms.plugins.tss.cortex.cfg` file to `etc/org.opennms.plugins.tss.prometheus.cfg` (or re-run `config:edit` under the new PID) after upgrading, otherwise the plugin falls back to default values. Java packages are unchanged.

![arch](assets/prometheus-remotewrite-plugin-arch.png "Plugin Architecture")

## Usage

Start a Prometheus `remote_write`-compatible backend. For Cortex, see https://cortexmetrics.io/docs/getting-started/

You can also download:

https://github.com/opennms-forge/stack-play/tree/main/standalone-cortex-minimal

and start with
`docker-compose up`

Build and install the plugin into your local Maven repository using:
```
mvn clean install
```

Enable the TSS and configure:
```
echo 'org.opennms.timeseries.strategy=integration
org.opennms.timeseries.tin.metatags.tag.node=${node:label}
org.opennms.timeseries.tin.metatags.tag.location=${node:location}
org.opennms.timeseries.tin.metatags.tag.geohash=${node:geohash}
org.opennms.timeseries.tin.metatags.tag.ifDescr=${interface:if-description}' >> ${OPENNMS_HOME}/etc/opennms.properties.d/cortex.properties
```

From the OpenNMS Karaf shell:
```
feature:repo-add mvn:org.opennms.plugins.timeseries/prometheus-remotewrite-karaf-features/2.1.0/xml
feature:install opennms-plugins-prometheus-remotewrite
```

Configure (you can omit that if you use the default values):
```
config:edit org.opennms.plugins.tss.prometheus

property-set writeUrl http://localhost:9009/api/prom/push
property-set readUrl http://localhost:9009/prometheus/api/v1
property-set maxConcurrentHttpConnections 100
property-set writeTimeoutInMs 1000
property-set readTimeoutInMs 1000
property-set callTimeoutInMs 10000
property-set metricCacheSize 1000
property-set externalTagsCacheSize 1000
property-set bulkheadMaxWaitDurationInMs 9223372036854775807
property-set asyncWrites false

config:update
```

`callTimeoutInMs` bounds a remote write call once the HTTP client has started it — connect, write,
backend processing and reading the acknowledgement. It is distinct from `writeTimeoutInMs`, which
only bounds writing the request body. Values outside `1 .. 2147483647` are clamped with a warning,
since OkHttp rejects anything larger and reads `0` as *no timeout at all*.

Note the bound is per call, not per `store()`: time spent queued in the HTTP client's dispatcher is
not counted. Keep `maxConcurrentHttpConnections` at or above the number of OpenNMS writer threads
(`writer_threads`, 16 by default), or a caller can wait a multiple of `callTimeoutInMs` while its
request sits in the queue.

`asyncWrites` controls whether a write is reported to the caller. With the default `false`, a
rejected write raises an error and the calling writer thread waits up to `callTimeoutInMs` for the
backend. Setting it to `true` restores the pre-2.2.0 behaviour: writes are dispatched and forgotten,
and failures are only visible in the plugin's own `samplesLost` meter. Use it if blocking the
OpenNMS writer threads is worse for you than losing the error — for example if you see ring buffer
drops when your backend degrades.

Be aware of how far the error actually travels. OpenNMS catches it in
`RingBufferTimeseriesWriter.onEvent`, but that logger is rate-limited to 5 messages every 30
seconds, and the batch is not added to OpenNMS' own write statistics on the failure path. During a
sustained outage the failures are therefore throttled in the log and missing from those statistics —
the plugin's `samplesWritten` and `samplesLost` meters are the reliable signal.

Update automatically:
```
bundle:watch *
```

## Backend tips (Cortex example)

### View the ring

http://localhost:9009/ring

### View internal metrics

http://localhost:9009/metrics

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, DCO sign-off, and
AI-assistance policy. Release process is documented in [RELEASING.md](RELEASING.md).

## License

Licensed under the GNU Affero General Public License v3.0. See [LICENSE.md](LICENSE.md).
