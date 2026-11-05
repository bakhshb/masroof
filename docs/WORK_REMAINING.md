# Remaining qualification work

The delivered personal-insights behavior is described in
[the design](PERSONAL_INSIGHTS_DESIGN.md) and [analytics policy](ANALYTICS.md).
Completed implementation plans and execution logs have been removed; the
unfinished qualification work is retained here without declaring it complete.

- [ ] Close the original personal-insights validation checkpoint against the
  relevant final commit: focused tests, lint/detekt, both APKs, upgrade/restore,
  process-death/device journeys and full PR/main CI. Check actual model readiness
  against the UI. This documentation cleanup does not certify that checkpoint.

## Qualification gates

Message families have no statistical model; learning is exact human-template memory.

Recurring seeds need at least 95% precision, ten percentage points of recall
improvement over rules and no increased false-positive rate. Validation and final
test each need at least 30 accepted independent series per cadence and 30 hard
negatives. Personal fitting holds out 20% of series and needs at least eight
held-out series (two recurring and two non-recurring), 95% precision, better
coverage and no increased false-positive rate.

The family Naive Bayes seed never qualified a family and was removed; message
learning is exact human-template memory. Insufficient real labels keep models ineligible. Synthetic
examples test behavior rather than establish real-world accuracy. Preserve
forecast evaluation gates, published comparisons, human decisions and financial
schema v18. Qualification is a data milestone, not an automatic consequence of
shipping UI or cleaner improvements.
