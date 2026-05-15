---
title: "Job listing"
sdk: java
spec_sections: ["6.6"]
order: 3
kind: feature
since: "1.0.0"
---

# Job listing — §6.6

**Feature flag:** `list_jobs`.

A client may request a read-only inventory of jobs visible to its session
principal. Filter by `status`, `agent`, or `created_after`.

## Wire shape

```json
{ "type": "session.list_jobs", "session_id": "sess_…", "id": "01J…",
  "payload": { "filter": { "status": ["running", "pending"] },
               "limit": 100, "cursor": null } }

{ "type": "session.jobs", "session_id": "sess_…",
  "payload": { "request_id": "01J…", "jobs": [ … ], "next_cursor": null } }
```

## Java surface

```java
Page<JobSummary> page = client.listJobs(JobFilter.all());
for (JobSummary s : page.items()) {
    System.out.println(s.jobId() + " " + s.agent() + " " + s.status());
}
```

`JobFilter` is a record with three optional fields. `Page<JobSummary>`
carries an optional `nextCursor` — `page.hasNext()` is the iteration
sentinel.

## Per-principal scope

The runtime returns only jobs whose `principal()` matches the requesting
session. Cross-principal job existence does not leak — there is no "you
are not authorised" response that distinguishes "no such job" from
"forbidden job"; the listing simply omits the entry.

## Example

[`examples/list-jobs/`](../../examples/list-jobs/) submits two jobs (one
fast, one blocking), validates that filtering by `status="running"`
returns exactly the blocker.
