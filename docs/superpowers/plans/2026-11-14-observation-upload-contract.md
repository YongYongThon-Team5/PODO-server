# Observation Upload Contract Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or executing-plans. User authorizes server-only compatibility fix and PR; no frontend changes.

**Goal:** Accept current frontend's images field while preserving existing files API and document exact multipart contract.
**Architecture:** Normalize one accepted multipart field to the same existing upload service. Retain validation/limits/idempotency/ownership. OpenAPI describes exclusive alternatives.
**Tech Stack:** Java21 Spring Boot MockMvc Testcontainers Swagger, no new dependencies.
**Spec:** This document's contract below is the complete small-change specification; no new product behavior.

## Global Constraints / Contract

POST /api/visits/{visitId}/observations accepts either files or images, each containing1–3 image parts. Never both names in a request; unknown file/text/query fields rejected as before. files backward compatible; images is alias needed by current frontend. Same perfile5MiB/image content processing/Idempotency-Key/account ownership/202status remain. No frontend, model-generation, state-machine or deployment changes. Swagger must expose multipart binary array min1max3 and exclusive field alternatives, Bearer and required idempotency header meaning.

## Review Focus

Mixed aliases must not bypass3-filelimit. Retrying same image bytes through the other alias must not create a second observation for same key. Unknown/text/query fields still reject. No generated frontend files. Uploaded data validation unchanged.

### Task 1: compatibility validation + OpenAPI + guide

Files: ObservationController.java, ObservationIntegrationTests.java, SwaggerConfigurationTests.java, docs/api/observation-upload.md and README link.
Interfaces: Existing service.upload(account,visit,key,List<UploadFile>) unchanged. Controller normalizes single files/images field only.

- [ ] Add MockMvc behavioral test images upload202, files upload202, mixedaliases/unknown/text/query/4parts reject, same key+bytes acrossalias idempotent. Verify RED for images before code.
- [ ] Implement minimal exclusive alias resolution and Swagger request schema. Existing limits/errors preserved. No aliases beyond the needed two.
- [ ] Focused observation+Swagger tests PASS, full suite once. Document exact frontend request contract and copyable FormData field examples (docs only).
- [ ] Independent review, fix important issues, commit/push feature branch and create dev-target PR. No merge/deploy.
