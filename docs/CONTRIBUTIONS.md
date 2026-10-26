# Proposed four-person contribution split

This is a proposed allocation of work. It does not claim that four named people completed the implementation. Replace the placeholders with member names and record their actual work before submission.

| Member | Proposed contribution | Main deliverables |
| --- | --- | --- |
| Person 1 | Data model and catalog backend | MongoDB collections and indexes; product, supplier, and warehouse APIs; validation; increasing distributed IDs. |
| Person 2 | Inventory correctness | Receipts, issues, transfers, transaction boundaries, idempotency, archive rules, and concurrent stock tests. |
| Person 3 | Distributed system components | Token bucket limiter, Redis protocol adapter, key-value cache, consistent hash ring, Nginx balancing, Docker setup, and scale tests. |
| Person 4 | User interface and warehouse sharing | Minimal frontend, autocomplete interaction, short links, owner and manager access, link revocation, browser checks, documentation, and demo preparation. |

All members should review the architecture, run the demo, and be able to explain how the components work together.
