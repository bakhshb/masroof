# Masroof design system

This document describes the shared tokens and components screens are built from. It is
reference for consistency, not an approval gate: `AGENTS.md` Priorities rank it below
financial correctness, data safety, architecture and a working screen. The code is the
source of truth; the original HTML prototype is not kept in the repository. Tokens live in
`ui-kit/src/main/kotlin/com/baraa/masroof/ui/theme/`; `MasroofDesign.version` is `approved-2026-10-10`.

## One source of visual values

| Concern | Authoritative source |
| --- | --- |
| Light/dark and semantic colors | `MasroofColors.kt`, `MasroofHeroColors.kt` |
| Launcher brand colors | `res/values/masroof_brand.xml` |
| Opacity and font weights | `MasroofDesign.kt` |
| Spacing and geometry | `MasroofReference.kt`, `MasroofSpacing.kt` |
| Typography and font weights | `MasroofTypography.kt`, `MasroofFontFamily.kt`, `MasroofFontWeights` |
| Shapes | `MasroofShapes.kt` |
| Icon geometry | `MasroofIconSizes.kt` |
| Icon meanings | `presentation/common/MasroofIcons.kt` |
| Elevation | `MasroofElevation.kt` |
| Localized terminology | `res/values/strings.xml`, `res/values-en/strings.xml` |

Screens use these tokens through MaterialTheme and shared components. Do not add
numeric dp/sp or color literals in feature screens. `ApprovedDesignBoundaryTest`
enforces this boundary. Cards use radius 20, controls 12, screen padding 16 and card padding 16 plus its 1-unit border inset,
flat elevation, and IBM Plex Sans Arabic. Filled light-mode buttons use onPrimary
white text. Account blue, card purple and liability amber each have paired
readable foregrounds in both modes.

## Shared screen behavior

The global header contains the brand and notifications. Home, financial
transactions, review and settings are in the bottom navigation. Home has one
interactive daily expenses chart. Account, credit aggregate, individual credit
and debit scopes reuse the same chart interaction. Detail pages display their
complete scoped transactions inline; tapping a transaction opens the existing
correction, ignore and commitment actions.

All entity summaries use the same neutral card shell, with type-specific facts:

- Account: calculated inflow/outflow/net movement, with its calculation described.
- Credit: recorded available balance, facility amount due and due date.
  Supplementary cards explain shared facility due; it is counted once in totals.
  The due amount and date come from the same statement. A missing latest date
  is displayed as unavailable rather than filled from an older statement.
- Debit: linked account and net purchases/refunds, without a fictional credit due.
- Loan: recorded remaining balance, latest installment and its recorded date.

Summary pages distinguish a failed load from an empty portfolio. A failed refresh
keeps the previous same-period data with an error notice; a failed initial load
does not display a misleading empty state.

Arabic expenses terminology is «المصاريف», «متوسط المصاريف اليومية» and
«المصاريف حسب التاجر». Merchant analysis requires at least two eligible purchases.

## Digits in every language

The display policy is `application/locale/AppDigits`: all visible digits use
`0123456789`. Arabic remains Arabic with RTL layout and Arabic month names.
`AppLocale.displayLocale` requests the `latn` numbering system; `MoneyUiFormatter`
uses Western digits and separators, omits zero fractional digits for whole amounts and preserves two decimal places for fractional amounts. All app text uses `MasroofText`, which also
normalizes imported names and SMS evidence at display time. Editable public text
uses `AppDigitsVisualTransformation` without modifying stored input or cursor
positions. Password fields keep their masking. Original SMS, financial facts and
backups retain their source values. `AppDigitsBoundaryTest` prevents direct text
bypasses and Eastern digits in display resources; native screen tests check text
in both themes and the Arabic date picker.

## Contextual explanations

`presentation/common/MasroofGuidance.kt` is the only contextual-help component.
Use `MasroofGuidanceButton` beside the subject, or `MasroofGuidanceLabel` for a
label and its help icon. The neutral circled exclamation is mapped in MasroofIcons.
`MasroofGuidanceStyle` exposes the icon size, popover width, padding, shape,
elevation and edge spacing. Colors and typography use the
shared MaterialTheme. The small anchored popover fits the window in RTL and LTR,
flips above its subject when necessary, and dismisses on outside press or Back.
Explanations are hidden until requested and never invoke a parent action.

Use this pattern for chart scope, averaging basis, account calculations, shared
credit dues, period adjustments, filters, registry tracking, backup passphrases,
re-analysis, updates, privacy, logs and restore help. Essential status, errors,
confirmation text and identifying data remain visible. The debug catalog includes
the shared help component. `MasroofGuidanceBoundaryTest` prevents inline hint text.

## Financial chart scope and direction

`application/dashboard/DashboardSpendingTrendsBuilder` computes every display
scope from the existing persisted dashboard context. Compose does not sum money.
Overall expenses include expenses, bills, fees, loan repayments, external outgoing
transfers and withdrawals, less refunds. Self-transfers and card settlements are
excluded to avoid double counting purchases. Home has one chart with two scopes:
Daily purchases is the default and includes attributed credit/Mada purchases,
cash withdrawals and external outgoing transfers, less card-attributed refunds;
bills, repayments and standalone fees stay in All daily expenses only. Scope tabs
share complete labels, equal columns and a high-contrast selected color pair.
Credit charts retain purchases, bills and ordinary fees less refunds. Mada charts
include only their confirmed card purchases, cash withdrawals and refunds;
an account link alone never assigns an unidentified withdrawal to a card.
FX requires a resolved local equivalent;
unresolved foreign amounts are not silently added to SAR.
Account charts and inline movements use the same registry-linked debit scope as
account summaries, including Google Pay purchases without an account SMS field
and verified debit refunds. Links remain qualified by bank and account identity.

Account chart titles keep their approved names. `AccountDailySpendingTrendsBuilder`
uses confirmed single-account source attribution for purchases, bills, loan
installments, card settlements, external outgoing transfers, withdrawals and
fees, less verified account refunds. Credit charges/refunds are excluded even
when their evidence also mentions an account; settlements remain account outflows.
The aggregate excludes self-transfers. Per-account charts include only the outgoing
self-transfer leg; receipts, salary and other inflows do not increase the expense line.
Unknown sources and unresolved currency conversions stay out. These scopes affect
charts, not the ledger or the deduplicated overall Home expense scope.

Aggregate credit and primary/supplementary detail charts enable the common
horizontal average line and legend, using each chart's own precomputed mean.
The account aggregate and detail scope explanations use separate contextual (!)
messages in both languages. Colors, typography and the existing titles are unchanged.

The daily mean uses all elapsed dates in the selected period including zero days;
completed periods use their full duration. It is not a fixed fourteen-day mean.
Future periods have no elapsed points or calculated mean. Home draws a dashed
horizontal line at the selected scope's actual mean, with a matching legend and
accessible description. The line is not positioned at a decorative midpoint.
`MasroofChartScopeTabs` and `MasroofChartAverageLegend` live in common components;
their geometry is in `MasroofReference`.
The selected date and amount are visible above the chart. Tap or scrub the line
to choose a day. Previous/next are available as screen-reader actions; no separate
previous/next day buttons are displayed.
Chronological points start on the right in Arabic and on the left in English;
date endpoints below the chart follow that same direction. Touch coordinates are
mapped back to the logical date index before showing that date's actual amount.

The developer design catalog is enabled only by the debug build flag
`-PmasroofDesignCatalog=true`; ordinary builds follow the approved Settings structure. Removed background gradients, raised-card shadows, entity
carousels, salary/statement credit-detail blocks and previous brand assets are
obsolete and must not be reintroduced.


## Visual verification

`ApprovedFidelityMatrixTest` renders the production page functions with synthetic
financial fixtures across 32 pages, Arabic/English and light/dark. It is a render smoke
test: it asserts each page renders, uses Western digits and keeps the Home shortcut row
layout, and saves captures for human review. It does not compare pixels against a
reference image. Native functional tests do not prove pixel fidelity.

Average-line raster assertions integrate foreground coverage across the physical
stroke band and verify its weighted center and dash gaps. A thin antialiased
stroke need not contain a fully opaque pixel in a single rounded row at 1x.
`AverageLinePixelContractTest` checks subpixel phases in both themes at multiple
densities and rejects missing, shifted, solid, wrong-color and wrong-width lines.
The production Home interaction test uses this same pixel contract against the
actual captured chart, with the expected mean independently computed from its data.

Reference measurements use the final computed CSS cascade, including inherited
button typography. Lucide outline icons and the original approved logo are reused
through shared components. Contextual guidance and screen-reader chart navigation
follow the later user approvals.

Home shortcuts share one card with four columns and light separators in both languages. Full labels use the same body typography; narrow windows scroll the single row rather than wrapping words or switching to two rows.

Home salary-period navigation uses `MasroofPeriodPill`: a visible salary heading
and Customize action above previous-arrow, centered date range, next-arrow.
The previous arrow is on the right in Arabic and left in English. Other pages
keep the read-only period display. Commitment summary handles both screen Back
and Android system Back through the same Home callback.

Dashboard summary and detail pages emit into the `ColumnScope` supplied by
`DashboardSummaryScaffold`. The scaffold owns scrolling, the period display,
loading/error gates and `MasroofSpacing.sectionGap`; pages add a nested layout
only for sections with their own grouping or spacing.

## Analysis screens: visual first (approved 2026-11-04)

A human-approved amendment for Spending, Forecasts, Commitments, data quality, forecast
accuracy and message-correction insights: each card leads with a chart, a large number or
a badge; explanations live behind `MasroofGuidance`, never as permanent paragraphs.

- Spending: one hero card holds the actual amount, a direction badge and the equal-length
  comparison bars. Composition is one `MasroofStackedBar` with clickable rows. History uses
  `MasroofDailyBarChart` per period with a dashed average line: missing periods keep an empty
  slot and partly covered periods are striped; no per-period text rows.
- Forecasts: a liability badge appears only when every qualified horizon's bias says the
  estimate ran low. Personal-model readiness is two `MasroofProgressRing`s. Horizon errors
  are bars with tests and direction in the subtitle.
- Commitments: the confirmed total leads, followed by a stacked bar, a `MasroofDueTimeline`
  from today to period end, and due rows nearest first with a countdown badge.
- Message corrections: a status badge, bars for the assistance flow, one stacked bar for used
  versus excluded corrections and a validation-template progress bar.

Shares, totals and countdowns come from `AnalysisPresentationBuilder`; Compose only
displays them. New geometry lives in `MasroofAnalyticsTokens`; previews are in the debug
catalog's shared charts section.

## Text fitting and documentation captures

`MasroofFitText` scales explicit money spans together with the base font. Shared tab labels also
use one common fit scale; below the minimum, money wraps instead of truncating. Retain the small
measurement helper until native autosizing preserves these contracts and visual parity. The
old Compose-version limitation no longer explains this choice.

The English and Arabic README screenshots in `docs/screenshots/` come from production fidelity
fixtures with synthetic financial data. Capture both languages with the same fixture and theme;
never substitute real account evidence or hand-edit financial values in screenshots.
