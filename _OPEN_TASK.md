IMPORTANT: To be done, after system is fully implemented

## Task 1

Make the hole application running in docker

**Status: DONE, verified.** `compose.yaml` has an `app` service (compose profile `app`, image `ledgerly:0.0.1-SNAPSHOT`, `local` profile, Postgres health check). Started with `docker compose --profile app up`: readiness 200, Swagger UI 200. ClamAV is pinned to `linux/amd64` because it has no arm64 image.

## Task 2

Create a MAKE file with all commands needed to run, stop, debug, lint, test the application

**Status: DONE, verified.** `Makefile` (`make` lists targets): deps, run, debug (port 5005), image, up, down, reset, logs, ps, format, lint, test, verify, test-one, clean. Checked: `make help`, `make -n up`, and `make lint` (passes). The Java targets need JDK 25 from mise on the PATH.

## Task 3

Add JSDOC like comments on methods, classes and variables to describe their intentional purpose or shape, ingoing and outgoing data. Do NOT describe JAVA internal methods.

**Status: DONE, verified (Spotless, compile, full `verify` green in the isolated copy).** About 350 doc comments added across all seven modules (154 files): types, records and their fields, use cases (inputs, outputs, errors thrown), repositories, events, controllers, filters, config. Left undocumented on purpose: constructors that only store injected dependencies, one-line event handlers covered by their class comment, and `@Override` methods (documented where declared). No behaviour changed.

## Task 4

Create Bruno files for api testing, including local setup, test cases

## Task 5

If not already happening, seed the database with a base set of data, so that testing can happen immeadabtly

## Task 6

Implementing lintern, prettier, test pipelines and implement automatic running checks on push to main via PR to Github

## Task 7 

About testing, see if you can make testing better. Tests should never test java or library methods, just custom code. It should test expected data flows, edge-cases and what methods should not do or allow

## Task 8

If not already existing, does a /docs endpoint exists for the OpenApiSpecs?

## Task 9

Its about hardening the local setup of supply-chain-attacks, secrets and such