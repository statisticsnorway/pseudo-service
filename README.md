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
release                        Release a new version. Update POMs and tag the new version in git
```

### Release

Release a new version of the package by running `make release`. This command
pushes the current state of `origin/master` *as well as* locally
committed changes to the `release` branch. This starts a workflow
that performs a minor version bump, a GitHub release, and a deployment
to the NAIS production environment.