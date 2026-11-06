IMPORTANT: To be done, after system is fully implemented

## Task 1

Make the hole application running in docker

**Status: DONE, verified.** `compose.yaml` has an `app` service (compose profile `app`, image `ledgerly:0.0.1-SNAPSHOT`, `local` profile, Postgres health check). Started with `docker compose --profile app up`: readiness 200, Swagger UI 200. ClamAV is pinned to `linux/amd64` because it has no arm64 image.

## Task 2

Create a MAKE file with all commands needed to run, stop, debug, lint, test the application

## Task 3

Add JSDOC like comments on methods, classes and variables to describe their intentional purpose or shape, ingoing and outgoing data. Do NOT describe JAVA internal methods.

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