# Dapla Pseudo Service

(De/)pseudonymization and export endpoints.

Browse the API docs at:
* [Swagger UI](https://pseudo-service.test.ssb.no/api-docs/swagger-ui)

## Pseudo rules

Pseudo rules are defined by:

* _name_ (used only for logging purposes)
* _pattern_ - [glob pattern](https://docs.oracle.com/javase/tutorial/essential/io/fileOps.html#glob) that matches fields 
  to be (de)/pseudonymized.
* _func_ - references a pseudo function (such as `fpe-anychar`, `fpe-fnr` or `fpe-digits`). The function references the
  pseudo secret to be used.


## Development

See `Makefile` for details/examples of common dev tasks.
```
build-all                      Build all and create docker image
build-mvn                      Build project and install to you local maven repo
build-docker                   Build dev docker image
init-local-config              Creates configuration files in the local directory
run-local                      Run the app locally (without docker)
release-dryrun                 Simulate a release in order to detect any issues
```

### Release

Release a new version of the package by pushing the newest changes on `master`
to the `release` branch. This should trigger a workflow that performs
the necessary version bumps and deploys to NAIS prod.

```bash
git checkout master
git pull
git checkout release
git merge master
git push
```