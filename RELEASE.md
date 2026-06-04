# Releasing

Update pom versions:
```
mvn versions:set -DnewVersion=2.1.1
```

Commit pom update:
```
git commit
```


Push commits:
```
git push
```

Tag:
```
git tag -u opennms@opennms.org -s v2.1.1
```

Rename:
```
mv org.opennms.plugins.timeseries.prometheus.remotewrite.assembly.kar-<VERSION>.kar opennms-prometheus-remotewrite-plugin.kar
```